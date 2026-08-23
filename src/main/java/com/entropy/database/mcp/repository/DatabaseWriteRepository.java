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
package com.entropy.database.mcp.repository;

import com.entropy.database.mcp.security.SqlValidator;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

/**
 * Database write operations repository.
 * Handles DDL and data modification with proper validation.
 */
public class DatabaseWriteRepository {

    private final JdbcTemplate jdbcTemplate;
    private final SqlValidator sqlValidator;

    public DatabaseWriteRepository(JdbcTemplate jdbcTemplate,
                                   SqlValidator sqlValidator) {
        this.jdbcTemplate = jdbcTemplate;
        this.sqlValidator = sqlValidator;
    }

    /**
     * Execute a DDL statement.
     */
    public Map<String, Object> executeDdl(String sql) {
        sqlValidator.validateDdl(sql);
        int affected = jdbcTemplate.update(sql);

        return Map.of(
            "affectedRows", affected,
            "success", true
        );
    }
}
