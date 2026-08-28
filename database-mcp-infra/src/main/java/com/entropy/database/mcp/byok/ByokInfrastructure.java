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
package com.entropy.database.mcp.byok;

import com.entropy.database.mcp.cache.DatabaseCache;
import com.entropy.database.mcp.monitor.DatabaseHealthMonitor;
import com.entropy.database.mcp.repository.DatabaseReadRepository;
import com.entropy.database.mcp.repository.ExecutionPlanRepository;
import com.entropy.database.mcp.security.QueryAuditLogger;

/**
 * Aggregates all per-datasource infrastructure components.
 * Replaces the 10-parameter ByokDataSourceContext constructor.
 */
public record ByokInfrastructure(
        DatabaseCache cache,
        DatabaseHealthMonitor healthMonitor,
        QueryAuditLogger auditLogger,
        DatabaseReadRepository readRepository,
        ByokWriteRepository writeRepository,
        ExecutionPlanRepository executionPlanRepository) {
}
