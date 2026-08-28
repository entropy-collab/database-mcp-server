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

import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import com.entropy.database.mcp.etl.JobExecutionEngine;
import com.entropy.database.mcp.properties.DatabaseProperties;
import com.entropy.database.mcp.properties.EtlConfig;
import com.entropy.database.mcp.security.SqlValidator;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Guards the wiring itself, not the components: the ETL engine reached production without a
 * {@link SqlValidator}, which turned {@code validateSourceSql} into a no-op while every other read
 * path was validated. A unit test of either class in isolation could not have caught that.
 */
class DatabaseConfigWiringTest {

    @Test
    void etlEngineValidatesSourceSqlThroughTheInjectedValidator() {
        SqlValidator validator = mock(SqlValidator.class);

        JobExecutionEngine engine = new DatabaseConfig().jobExecutionEngine(
                mock(DynamicDataSourceManager.class),
                new DatabaseProperties(true, "h2", null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null),
                new EtlConfig(1, 100),
                Runnable::run,
                null,
                validator);

        engine.validateSourceSql("SELECT 1 FROM DUAL");

        verify(validator).validateSelect("SELECT 1 FROM DUAL");
    }
}
