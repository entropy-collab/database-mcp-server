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
package com.entropy.database.mcp.dialect;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class DialectResolverTest {

    private final DialectResolver resolver = new DialectResolver();

    @Test
    void resolvesOracleDialect() {
        var dialect = resolver.resolve("oracle", null);
        Assertions.assertThat(dialect).isInstanceOf(OracleDialect.class);
    }

    @Test
    void resolvesMySqlDialect() {
        var dialect = resolver.resolve("mysql", null);
        Assertions.assertThat(dialect).isInstanceOf(MySqlDialect.class);
    }

    @Test
    void resolvesPostgresDialect() {
        var dialect = resolver.resolve("postgres", null);
        Assertions.assertThat(dialect).isInstanceOf(PostgresDialect.class);
    }

    @Test
    void resolvesPostgresDialectCaseInsensitive() {
        var dialect = resolver.resolve("PostgreSQL", null);
        Assertions.assertThat(dialect).isInstanceOf(PostgresDialect.class);
    }

    @Test
    void fallsBackToGenericDialect() {
        var dialect = resolver.resolve("h2", null);
        Assertions.assertThat(dialect).isInstanceOf(GenericDialect.class);
    }

    @Test
    void fallsBackToGenericDialectWhenNull() {
        var dialect = resolver.resolve(null, null);
        Assertions.assertThat(dialect).isInstanceOf(GenericDialect.class);
    }
}
