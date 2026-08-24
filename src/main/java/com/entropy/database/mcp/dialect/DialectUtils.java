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

/**
 * Utility methods for dialect and driver class inference from JDBC URLs.
 */
public final class DialectUtils {

    private DialectUtils() {
    }

    public static String inferDialect(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return "generic";
        }
        if (jdbcUrl.startsWith("jdbc:oracle:")) return "oracle";
        if (jdbcUrl.startsWith("jdbc:mysql:") || jdbcUrl.startsWith("jdbc:mariadb:")) return "mysql";
        if (jdbcUrl.startsWith("jdbc:postgresql:")) return "postgres";
        if (jdbcUrl.startsWith("jdbc:sqlserver:")) return "sqlserver";
        if (jdbcUrl.startsWith("jdbc:sqlite:")) return "sqlite";
        if (jdbcUrl.startsWith("jdbc:db2:")) return "db2";
        if (jdbcUrl.startsWith("jdbc:h2:")) return "h2";
        return "generic";
    }

    public static String inferDriverClassName(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return null;
        }
        if (jdbcUrl.startsWith("jdbc:oracle:")) return "oracle.jdbc.OracleDriver";
        if (jdbcUrl.startsWith("jdbc:mysql:")) return "com.mysql.cj.jdbc.Driver";
        if (jdbcUrl.startsWith("jdbc:mariadb:")) return "org.mariadb.jdbc.Driver";
        if (jdbcUrl.startsWith("jdbc:postgresql:")) return "org.postgresql.Driver";
        if (jdbcUrl.startsWith("jdbc:sqlserver:")) return "com.microsoft.sqlserver.jdbc.SQLServerDriver";
        if (jdbcUrl.startsWith("jdbc:sqlite:")) return "org.sqlite.JDBC";
        if (jdbcUrl.startsWith("jdbc:db2:")) return "com.ibm.db2.jdbc.DB2Driver";
        if (jdbcUrl.startsWith("jdbc:h2:")) return "org.h2.Driver";
        return null;
    }
}
