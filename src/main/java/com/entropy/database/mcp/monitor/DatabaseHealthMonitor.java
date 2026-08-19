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
package com.entropy.database.mcp.monitor;

import com.entropy.database.mcp.cache.DatabaseCache;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.HashMap;
import java.util.Map;

/**
 * Database health checker and statistics provider.
 */
@Component
public class DatabaseHealthMonitor {

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCache cache;
    private final QueryStats queryStats = new QueryStats();

    public DatabaseHealthMonitor(JdbcTemplate jdbcTemplate, DatabaseCache cache) {
        this.jdbcTemplate = jdbcTemplate;
        this.cache = cache;
    }

    public Map<String, Object> getHealthStatus() {
        Map<String, Object> status = new HashMap<>();
        try (Connection conn = jdbcTemplate.getDataSource().getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();

            status.put("status", "healthy");
            status.put("databaseProductName", meta.getDatabaseProductName());
            status.put("databaseProductVersion", meta.getDatabaseProductVersion());
            status.put("driverName", meta.getDriverName());
            status.put("driverVersion", meta.getDriverVersion());
            status.put("url", meta.getURL());
            status.put("user", meta.getUserName());
        } catch (Exception e) {
            status.put("status", "unhealthy");
            status.put("error", e.getMessage());
        }
        return status;
    }

    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("query", queryStats.toSummary());
        stats.put("cache", cache.getStatistics());
        return stats;
    }

    public void recordQuery(long durationMs, int rowsReturned, boolean success) {
        queryStats.recordQuery(durationMs, rowsReturned, success);
    }

    public QueryStats getQueryStats() {
        return queryStats;
    }
}
