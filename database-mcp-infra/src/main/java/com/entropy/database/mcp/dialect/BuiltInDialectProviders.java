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

import java.util.List;

/**
 * The eight dialects that ship with the server, expressed as {@link DialectProvider}s.
 *
 * <p>Before this class existed, {@link DialectResolver} carried two parallel {@code switch}
 * statements — one keyed by dialect name, one keyed by JDBC URL prefix — and consulted the
 * ServiceLoader only as an afterthought. Adding a dialect meant editing three places and it was
 * impossible for a third party to participate in {@code dialect=auto} detection at all.
 *
 * <p>Everything a dialect needs to be selectable now lives in one entry here: its canonical name,
 * its aliases, and the JDBC URL prefixes it owns. External dialects declare the same three things
 * and are treated identically.
 *
 * <p>Registered in {@code META-INF/services/com.entropy.database.mcp.dialect.DialectProvider}.
 * The nested classes must stay {@code public} with a no-arg constructor: {@link
 * java.util.ServiceLoader} instantiates them reflectively.
 */
public final class BuiltInDialectProviders {

    private BuiltInDialectProviders() {
    }

    /**
     * Shared plumbing: a built-in provider is a dialect instance plus the names and URL prefixes
     * that select it. The dialect is created once per provider and shared by every caller, which is
     * safe because dialects are stateless — {@code DialectStatelessnessTest} enforces that.
     */
    private abstract static class BuiltIn implements DialectProvider {

        private final DatabaseDialect dialect;
        private final List<String> aliases;
        private final List<String> urlPrefixes;

        BuiltIn(DatabaseDialect dialect, List<String> aliases, List<String> urlPrefixes) {
            this.dialect = dialect;
            this.aliases = aliases;
            this.urlPrefixes = urlPrefixes;
        }

        @Override
        public String getName() {
            return dialect.getDialectName();
        }

        @Override
        public DatabaseDialect getDialect() {
            return dialect;
        }

        @Override
        public List<String> getAliases() {
            return aliases;
        }

        @Override
        public List<String> getJdbcUrlPrefixes() {
            return urlPrefixes;
        }

        @Override
        public boolean isBuiltIn() {
            return true;
        }
    }

    public static final class Oracle extends BuiltIn {
        public Oracle() {
            super(new OracleDialect(), List.of(), List.of("jdbc:oracle:"));
        }
    }

    /** MariaDB speaks the MySQL wire protocol and shares its metadata queries. */
    public static final class MySql extends BuiltIn {
        public MySql() {
            super(new MySqlDialect(), List.of("mariadb"), List.of("jdbc:mysql:", "jdbc:mariadb:"));
        }
    }

    public static final class Postgres extends BuiltIn {
        public Postgres() {
            super(new PostgresDialect(), List.of("postgresql"), List.of("jdbc:postgresql:"));
        }
    }

    public static final class SqlServer extends BuiltIn {
        public SqlServer() {
            super(new SqlServerDialect(), List.of("mssql"), List.of("jdbc:sqlserver:"));
        }
    }

    public static final class Sqlite extends BuiltIn {
        public Sqlite() {
            super(new SqliteDialect(), List.of(), List.of("jdbc:sqlite:"));
        }
    }

    public static final class Db2 extends BuiltIn {
        public Db2() {
            super(new Db2Dialect(), List.of(), List.of("jdbc:db2:"));
        }
    }

    public static final class H2 extends BuiltIn {
        public H2() {
            super(new H2Dialect(), List.of(), List.of("jdbc:h2:"));
        }
    }

    /**
     * The fallback. Claims no URL prefix on purpose: it is what {@link DialectResolver} returns
     * when nothing else matches, not something a URL should resolve to.
     */
    public static final class Generic extends BuiltIn {
        public Generic() {
            super(new GenericDialect(), List.of(), List.of());
        }
    }
}
