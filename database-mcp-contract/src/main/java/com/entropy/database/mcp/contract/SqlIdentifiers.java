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
package com.entropy.database.mcp.contract;

import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpValidationException;

import java.util.regex.Pattern;

/**
 * 「裸标识符」的单一判定入口：表名、列名、schema 名、DB link 名等一切会被<b>字面拼进 SQL</b>
 * 的名字都以这里为准。
 *
 * <p><b>为什么要有这个类</b>：此前仓库里有四套内容相近但不一致的正则——
 * {@code ValidationUtils.validateIdentifier}（{@code [A-Za-z][A-Za-z0-9_$#]*}，不许下划线开头）、
 * {@code DialectUtils.isPlainIdentifier}、{@code BatchInsertHelper}（各自允许下划线开头）、以及
 * {@code DatabaseDialect.isValidIdentifier}（{@code [a-zA-Z_][a-zA-Z0-9_]*}，不许 {@code $#}）。
 * 同一个表名在不同入口的接受集不同，于是出现「A 处拒、B 处放」的判定漂移：同步 upsert 能过、异步
 * ETL 的同一张表被拒（或反过来），而这种差异只在运行时以一条参数校验错误的形式露出来，读代码
 * 看不出哪一套才是规则。判定必须只有一处，收紧或放宽才能一次性生效。
 *
 * <p><b>规则</b>：{@code [A-Za-z_][A-Za-z0-9_$#]*}，最长 {@value #MAX_LENGTH} 字符。
 *
 * <p><b>为什么允许 {@code $} 与 {@code #}</b>：它们是 Oracle 标识符的合法字符，数据字典本身就在用
 * （{@code V$SESSION}、{@code SYS#}、{@code TBL$TMP}）。禁掉等于让本服务连自己要查的系统对象都
 * 拼不出来，而调用方只会看到一条「非法字符」——把合法输入判成攻击是更糟的失败方式。它们对
 * 字符串拼接没有危险：既不能闭合字面量，也不能开注释或分隔语句。
 *
 * <p><b>为什么允许下划线开头</b>：这是四套实现里三套的行为，而 {@code ValidationUtils} 那一套的
 * 收紧没有安全收益（下划线同样不能逃逸字面量），却会拒掉 {@code _tmp_stage} 这类真实存在的
 * 临时表名。统一时取「不误伤合法输入」的那一版。
 *
 * <p><b>为什么不支持带引号的标识符</b>：本服务从不生成带引号的用户输入标识符——所有拼接点给到的
 * 都是这里判过的裸名字。放开引号意味着要在每个方言里正确处理引号转义（Oracle/PostgreSQL 的
 * {@code ""}、MySQL 的 {@code ``}、SQL Server 的 {@code []}），任何一处漏转义就是一个注入点；
 * 收益只是让调用方能用大小写敏感或含空格的怪名字。这个交换不成立，所以引号、空格、分号、
 * {@code --}、{@code /*}、反斜杠与非 ASCII 一律拒绝，而不是转义后放行。
 *
 * <p><b>为什么有 {@value #MAX_LENGTH} 的长度上限</b>：与仓库里既有的上限对齐
 * （{@code CrossDatabaseTools} 的 {@code {0,127}}、{@code QueryAuditLoggerImpl} 的脱敏正则同为 128），
 * 也覆盖各数据库的实际上限（Oracle 12.2+ 与 PostgreSQL 均为 128 字节，MySQL 64）。没有上限时一个
 * 几十万字符的「合法」名字会原样进 SQL 文本、进日志、进审计记录。
 *
 * <p><b>这一层是下限，不是全部</b>：{@code DatabaseDialect.isValidIdentifier} 仍然是方言可以<b>再
 * 收紧</b>的那一层（例如某个方言不接受 {@code $}）。本类只负责「所有入口至少都拒掉什么」。
 *
 * <p>零依赖：本类住在 contract 这个零第三方依赖的叶子模块里，只用 JDK 与同模块的异常类型，
 * 这样 core / dialect / infra 三层都能直接委派给它，不产生任何新的模块依赖方向。
 */
public final class SqlIdentifiers {

    /** 标识符最大长度，见类注释「为什么有长度上限」。 */
    public static final int MAX_LENGTH = 128;

    /**
     * 规则的人类可读描述，用在错误信息里。抽成常量是因为它此前被手抄进
     * {@code BatchInsertHelper} 的异常文案，正则一改文案就变成谎报。
     */
    public static final String RULE_DESCRIPTION = "[A-Za-z_][A-Za-z0-9_$#]* (max " + MAX_LENGTH + " chars)";

    /**
     * 长度上限直接编进正则，而不是分两步判：分两步就会出现「先过正则、再过长度」两个错误分支，
     * 而调用方只关心「是不是裸标识符」。
     */
    private static final Pattern PLAIN_IDENTIFIER =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]{0," + (MAX_LENGTH - 1) + "}");

    private SqlIdentifiers() {
    }

    /**
     * 是否是裸标识符。
     *
     * <p>刻意<b>不做 trim</b>：前后空白在这里是「拒绝」而不是「先修好再判」。调用方如果确实要接受
     * 带空白的输入（{@code DialectUtils.schemaExpression} 就是），必须自己先 trim 再进来——这样
     * 「拼进 SQL 的到底是哪个字符串」在调用点是看得见的，而不是本方法悄悄换掉了一个值。
     *
     * @param name 待判定的名字，{@code null} 一律返回 {@code false}
     */
    public static boolean isPlain(String name) {
        return name != null && PLAIN_IDENTIFIER.matcher(name).matches();
    }

    /**
     * 校验裸标识符，不合规则就抛 {@link McpValidationException}。
     *
     * <p>错误信息刻意区分「空」与「非法字符」两种情况并回显原值：这两种输入的修法完全不同，
     * 合成一条「invalid identifier」会让调用方（多数时候是模型）只能靠猜。
     *
     * @param name 待校验的名字
     * @param what 参数名，直接进错误信息，让调用方知道是哪个入参被拒
     * @throws McpValidationException 名字为空、含非法字符或超长
     */
    public static void requirePlain(String name, String what) {
        if (name == null || name.isBlank()) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    what + " cannot be blank");
        }
        if (name.length() > MAX_LENGTH) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    what + " exceeds the " + MAX_LENGTH + "-character identifier limit: " + name.length()
                            + " characters");
        }
        if (!isPlain(name)) {
            throw new McpValidationException(ErrorCode.PARAMETER_VALIDATION_FAILED,
                    what + " contains invalid characters: " + name);
        }
    }
}
