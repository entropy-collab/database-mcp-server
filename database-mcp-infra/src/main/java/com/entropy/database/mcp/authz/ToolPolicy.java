/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.entropy.database.mcp.authz;

import facet.core.eval.Checker;
import facet.core.eval.Schema;
import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Perm;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;
import facet.core.ir.Tuple;
import facet.store.memory.MemoryAttrSource;
import facet.store.memory.MemoryTupleSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import static facet.dsl.rebac.Rebac.anyOf;
import static facet.dsl.rebac.Rebac.direct;

/**
 * 授权策略：schema（规则）加元组（数据）。
 *
 * <h2>规则</h2>
 * <pre>
 * connection / table:
 *   reader / writer / admin  —— 直接授权，数据来自配置
 *   read  = reader ∪ writer ∪ admin
 *   write = writer ∪ admin
 * </pre>
 * writer 自动能读、admin 自动两者都能，所以配置里不需要给同一个人写两条。这是把"角色的包含关系"
 * 写进<b>规则</b>而不是写进<b>数据</b>——后者意味着每次加人都要记得展开，而漏展开不会报错。
 *
 * <h2>两个粒度，为什么不用 {@code Through} 串起来</h2>
 * <p>"连接级 writer 自然能写这条连接上的每张表"这条包含关系<em>没有</em>写成
 * {@code through("parent", ref("write"))}，而是由 {@code ConnectionAuthorizer} 先判连接、判不过
 * 再逐表判。原因是 {@code Through} 需要一条 {@code table#parent@connection} 的元组真实存在，而
 * 这里根本拿不到它：BYOK 的连接名是运行时由 {@code createNamedConnection} 创建的，表名则来自
 * 正在执行的那条 SQL——两者都不在启动时已知，于是"哪些表属于哪条连接"这份数据只能在每次判定时
 * 现造。现造意味着每个请求重建一份 {@code TupleSource}，也意味着这份策略不再是不可变的。
 * 把这条包含关系放在调用侧的一次短路里，没有"漏展开"的失败形态——它是一条规则，不是每条数据
 * 都要复制一遍的东西。
 *
 * <p>表对象的 id 带连接名前缀（{@code prod/APP.USERS}）。少了这个前缀，{@code dev} 上的表级授权
 * 会落到 {@code prod} 的同名表上。
 *
 * <p>没声明 {@code listable}：目前没有"我能访问哪些连接 / 哪些表"这个需求，而声明了却不用会让
 * schema 看起来支持一件它没被测过的事。
 *
 * <h2>数据</h2>
 * <p>元组来自配置，进程启动时一次性装进 {@code MemoryTupleSource}。为什么不是数据库：
 * 授权数据量很小（连接数 × 人数），而配置驱动让策略变更走 code review、在 git 里留痕。
 * 需要不重启改策略时再换 {@code facet-store-pg}——不过那要 PostgreSQL，H2 上跑不了它的
 * {@code WITH RECURSIVE} / {@code ctid} / 顾问锁。
 *
 * <p><b>不可变。</b>构造完成后 schema 与元组都不再变化，所以这个对象可以被并发共享，
 * 也不需要考虑"判定中途策略变了"。
 */
public final class ToolPolicy {

    /** 被授权的资源类型。 */
    static final ObjectType CONNECTION = new ObjectType("connection");
    static final ObjectType TABLE = new ObjectType("table");

    static final Rel READ = new Rel("read");
    static final Rel WRITE = new Rel("write");

    private final Checker checker;

    private ToolPolicy(Schema schema, MemoryTupleSource tuples) {
        this.checker = new Checker(schema, tuples, new MemoryAttrSource());
    }

    /**
     * 按配置构建策略。
     *
     * @throws IllegalArgumentException 任何一条 grant 非法（校验在 {@code Grant} 的构造期）
     */
    public static ToolPolicy of(List<ToolAuthzProperties.Grant> grants) {
        return new ToolPolicy(schema(), new MemoryTupleSource().write(tuples(grants)));
    }

    /** 判定入口。必须在 {@code Ctx.run} 之内调用——主体从上下文取。 */
    Checker checker() {
        return checker;
    }

    /** 资源引用。集中在这里，避免每个调用点自己拼类型名。 */
    static ObjectRef connection(String name) {
        return new ObjectRef(CONNECTION, name);
    }

    /**
     * 表的资源引用。{@code table} 必须已经归一化成大写——两个来源（配置里的 grant、从 SQL 里取出的
     * 对象名）各自归一化，这里不再兜一次，否则"忘了归一化"会表现为一次静默的拒绝。
     */
    static ObjectRef table(String connection, String table) {
        return new ObjectRef(TABLE, connection + '/' + table);
    }

    private static Schema schema() {
        return facet.dsl.rebac.Rebac.define()
                .type("connection", ToolPolicy::roles)
                .type("table", ToolPolicy::roles)
                .build();
    }

    /**
     * 两个类型的关系声明逐字相同，所以只写一遍——写两遍就会有一天只改了其中一遍。
     *
     * <p>角色清单与读写包含关系都从 {@link AuthzRole} 生成，<b>不在这里写死</b>：
     * 手写 {@code anyOf(direct("reader"), direct("writer"), direct("admin"))} 的坏法是加一个角色时
     * 只改了配置白名单、忘了改这里——配置会通过校验，那个角色也确实落进元组，但它不属于任何
     * computed 关系，于是判定恒为拒绝，而没有任何报错指向这一行。
     */
    private static void roles(facet.dsl.rebac.Rebac.TypeBuilder t) {
        for (AuthzRole role : AuthzRole.values()) {
            t.tuples(role.configName());
        }
        t.computed(READ.name(), anyOf(directsOf(AuthzRole::grantsRead)))
                .computed(WRITE.name(), anyOf(directsOf(AuthzRole::grantsWrite)));
    }

    /** {@code anyOf} 的入参：满足断言的每个角色一条 {@code direct}。 */
    private static Perm[] directsOf(Predicate<AuthzRole> grants) {
        return Arrays.stream(AuthzRole.values())
                .filter(grants)
                .map(role -> direct(role.configName()))
                .toArray(Perm[]::new);
    }

    private static List<Tuple> tuples(List<ToolAuthzProperties.Grant> grants) {
        var out = new ArrayList<Tuple>(grants.size());
        for (var grant : grants) {
            ObjectRef object = grant.table() == null
                    ? connection(grant.connection())
                    : table(grant.connection(), grant.table());
            out.add(new Tuple(
                    object,
                    relationFor(grant.role()),
                    new SubjectRef.Principal(new ObjectType(grant.subjectType()), grant.subjectId())));
        }
        return out;
    }

    /**
     * 角色名到关系名的映射。{@code Grant} 已经把 role 收进白名单，所以这里不需要兜底分支——
     * 真正的兜底在 {@link AuthzRole#of}，它对白名单外的值抛异常。
     */
    private static Rel relationFor(String role) {
        return new Rel(AuthzRole.of(role).configName());
    }
}
