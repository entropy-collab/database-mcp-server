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
package com.entropy.database.mcp.config;

import com.entropy.database.mcp.credential.CredentialCipher;
import com.entropy.database.mcp.properties.BackupProperties;
import com.entropy.database.mcp.properties.CatalogProperties;
import com.entropy.database.mcp.properties.CdcProperties;
import com.entropy.database.mcp.properties.ConfiguredConnectionProperties;
import com.entropy.database.mcp.properties.CredentialCipherProperties;
import com.entropy.database.mcp.properties.LineageProperties;
import com.entropy.database.mcp.properties.OptimizerProperties;
import com.entropy.database.mcp.properties.QualityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置真的绑进来了吗。
 *
 * <p>{@code ConfigurationPropertiesRegisteredTest} 只证明每个 properties 类都是个 bean；
 * 这条测试证明<b>配置值到得了那个 bean</b>——两件完全不同的事。
 *
 * <p>为什么必须有：本包里有 8 个 properties record 同时声明了「紧凑规范构造器」和「无参便捷构造器」，
 * 两个都是 public。Boot 的 {@code DefaultBindConstructorProvider} 在多个非私有构造器且没有
 * {@code @ConstructorBinding} 指明时会放弃值对象绑定，退化成 JavaBean 绑定；record 没有 setter，
 * 于是整段配置被<b>静默忽略</b>，bean 里只有无参构造器的默认值。没有任何日志、任何异常。
 *
 * <p>0.4.0 实测撞到过：启动加 {@code --entropy.mcp.database.backup.retention-days=3}，
 * {@code getBackupConfig} 依然回 7。最严重的一处是
 * {@code entropy.mcp.database.connections.*}——预声明连接（也是文档里推荐的"不让口令过对话历史"
 * 的那条路）整段失效，而失效方式只是 {@code listConnections} 永远空着。
 *
 * <p>所以每个断言都刻意用<b>与默认值不同</b>的数字：断言"等于默认值"在这个失效模式下永远是绿的。
 */
@SpringBootTest(properties = {
        "entropy.mcp.database.enabled=true",
        "entropy.mcp.database.dialect=generic",
        "entropy.mcp.security.enabled=false",

        "entropy.mcp.database.backup.retention-days=3",
        "entropy.mcp.database.backup.max-records=42",
        "entropy.mcp.database.catalog.max-search-results=7",
        "entropy.mcp.database.cdc.max-events-per-poll=11",
        "entropy.mcp.database.lineage.max-traversal-depth=4",
        "entropy.mcp.database.optimizer.max-index-recommendations=2",
        "entropy.mcp.database.quality.max-sample-rows=99",
        "entropy.mcp.security.credential-cipher.max-ttl=3m",
        // 空串：容器编排里 PRIVATE_KEY: ${MCP_CREDENTIAL_PRIVATE_KEY} 在变量缺失时给的就是这个。
        // 属性"存在但为空"必须等同于没配，否则每个没启用密文凭证的部署都会启动失败。
        "entropy.mcp.security.credential-cipher.private-key=",

        // 连不上的地址 + required=false：启动只会 WARN 跳过，而属性绑定本身照样被断言到。
        "entropy.mcp.database.connections.binding-probe.jdbc-url=jdbc:postgresql://127.0.0.1:1/nope",
        "entropy.mcp.database.connections.binding-probe.username=probe",
        "entropy.mcp.database.connections.binding-probe.required=false"
})
class ConfigurationPropertiesBindingTest {

    @Autowired
    private BackupProperties backup;
    @Autowired
    private CatalogProperties catalog;
    @Autowired
    private CdcProperties cdc;
    @Autowired
    private LineageProperties lineage;
    @Autowired
    private OptimizerProperties optimizer;
    @Autowired
    private QualityProperties quality;
    @Autowired
    private CredentialCipherProperties credentialCipher;
    @Autowired
    private ConfiguredConnectionProperties connections;
    @Autowired
    private ApplicationContext context;

    @Test
    void bindsEveryRecordThatAlsoDeclaresANoArgConstructor() {
        assertThat(backup.retentionDays()).isEqualTo(3);
        assertThat(backup.maxRecords()).isEqualTo(42);
        assertThat(catalog.maxSearchResults()).isEqualTo(7);
        assertThat(cdc.maxEventsPerPoll()).isEqualTo(11);
        assertThat(lineage.maxTraversalDepth()).isEqualTo(4);
        assertThat(optimizer.maxIndexRecommendations()).isEqualTo(2);
        assertThat(quality.maxSampleRows()).isEqualTo(99);
        assertThat(credentialCipher.maxTtl()).isEqualTo(Duration.ofMinutes(3));
    }

    /** 预声明连接这条路的绑定单独断言：它失效时的表现最隐蔽（listConnections 空着而已）。 */
    @Test
    void bindsDeclaredConnections() {
        assertThat(connections.connections()).containsKey("binding-probe");
        assertThat(connections.connections().get("binding-probe").username()).isEqualTo("probe");
        assertThat(connections.connections().get("binding-probe").required()).isFalse();
    }

    /** 私钥没配时机制关闭，且不能因为"没配"就把 bean 也丢了——下游要靠它读 maxTtl 与开关。 */
    @Test
    void keepsTheCipherPropertiesBeanEvenWithoutAPrivateKey() {
        assertThat(credentialCipher.enabled()).isFalse();
        assertThat(credentialCipher.requireSealedCredentials()).isFalse();
    }

    /**
     * 私钥是空串（属性在、值为空）时服务照样起来，且解密器 bean 不可用。
     *
     * <p>断言的是下游真正用的取法：{@code ObjectProvider.getIfAvailable()}。
     */
    @Test
    void treatsABlankPrivateKeyAsFeatureOff() {
        assertThat(context.getBeanProvider(CredentialCipher.class).getIfAvailable()).isNull();
    }
}
