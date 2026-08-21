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

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

public class DialectResolver {

    private final java.util.Map<String, DatabaseDialect> customDialects;

    public DialectResolver() {
        this.customDialects = loadCustomDialects();
    }

    public DatabaseDialect resolve(String dialectName, DataSource dataSource) {
        if (dialectName == null || dialectName.isBlank()) {
            return new GenericDialect();
        }
        String lower = dialectName.toLowerCase();
        
        // Check custom dialects first
        if (customDialects.containsKey(lower)) {
            return customDialects.get(lower);
        }
        
        return switch (lower) {
            case "oracle" -> new OracleDialect();
            case "mysql" -> new MySqlDialect();
            case "postgres", "postgresql" -> new PostgresDialect();
            case "sqlserver", "mssql" -> new SqlServerDialect();
            case "sqlite" -> new SqliteDialect();
            case "db2" -> new Db2Dialect();
            case "h2" -> new H2Dialect();
            case "auto" -> detectFromJdbcUrl(dataSource);
            default -> new GenericDialect();
        };
    }

    private Map<String, DatabaseDialect> loadCustomDialects() {
        Map<String, DatabaseDialect> map = new HashMap<>();
        ServiceLoader<DialectProvider> loader = ServiceLoader.load(DialectProvider.class);
        for (DialectProvider provider : loader) {
            map.put(provider.getName().toLowerCase(), provider.getDialect());
        }
        return map;
    }

    private DatabaseDialect detectFromJdbcUrl(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            var url = connection.getMetaData().getURL();
            String dialect = DialectUtils.inferDialect(url);
            return switch (dialect) {
                case "oracle" -> new OracleDialect();
                case "mysql" -> new MySqlDialect();
                case "postgres" -> new PostgresDialect();
                case "sqlserver" -> new SqlServerDialect();
                case "sqlite" -> new SqliteDialect();
            case "db2" -> new Db2Dialect();
            case "h2" -> new H2Dialect();
            default -> new GenericDialect();
            };
        } catch (Exception e) {
            return new GenericDialect();
        }
    }
}
