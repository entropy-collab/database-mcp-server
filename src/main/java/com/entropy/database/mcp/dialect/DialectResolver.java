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
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.lang.Nullable;

public class DialectResolver {

    public DatabaseDialect resolve(String dialectName, DataSource dataSource) {
        if (dialectName == null || dialectName.isBlank()) {
            return new GenericDialect();
        }
        return switch (dialectName.toLowerCase()) {
            case "oracle" -> new OracleDialect();
            case "mysql" -> new MySqlDialect();
            case "postgres", "postgresql" -> new PostgresDialect();
            case "auto" -> detectFromJdbcUrl(dataSource);
            default -> new GenericDialect();
        };
    }

    private DatabaseDialect detectFromJdbcUrl(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            var url = connection.getMetaData().getURL();
            if (url.startsWith("jdbc:oracle:")) {
                return new OracleDialect();
            } else if (url.startsWith("jdbc:mysql:")) {
                return new MySqlDialect();
            } else if (url.startsWith("jdbc:postgresql:")) {
                return new PostgresDialect();
            }
            return new GenericDialect();
        } catch (Exception e) {
            return new GenericDialect();
        }
    }
}
