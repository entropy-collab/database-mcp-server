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
package com.entropy.database.mcp.properties;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class DatabasePropertiesTest {

    @Test
    void defaultsDialectToOracleWhenBlank() {
        var properties = new DatabaseProperties(
            true,
            "   ",
            new DatabaseProperties.QueryProperties(100, 30, true, 10000),
            new DatabaseProperties.DdlProperties(false)
        );

        Assertions.assertThat(properties.dialect()).isEqualTo("oracle");
    }

    @Test
    void keepsExplicitDialect() {
        var properties = new DatabaseProperties(
            true,
            "mysql",
            new DatabaseProperties.QueryProperties(100, 30, true, 10000),
            new DatabaseProperties.DdlProperties(false)
        );

        Assertions.assertThat(properties.dialect()).isEqualTo("mysql");
    }

    @Test
    void queryPropertiesClampToDefaults() {
        var properties = new DatabaseProperties.QueryProperties(0, 0, true, 0);

        Assertions.assertThat(properties.maxRows()).isEqualTo(100);
        Assertions.assertThat(properties.timeoutSeconds()).isEqualTo(30);
        Assertions.assertThat(properties.cacheEnabled()).isTrue();
    }

    @Test
    void ddlPropertiesNormalizesNull() {
        var properties = new DatabaseProperties.DdlProperties(false);

        Assertions.assertThat(properties.allowed()).isFalse();
    }
}
