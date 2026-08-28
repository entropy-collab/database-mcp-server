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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the data lineage module.
 */
@ConfigurationProperties(prefix = "entropy.mcp.database.lineage")
public record LineageProperties(
    boolean enabled,
    boolean foreignKeyEnabled,
    boolean viewDependencyEnabled,
    int maxTraversalDepth,
    boolean autoAnalyze,
    int maxTablesPerGraph
) {
    public LineageProperties {
        enabled = Boolean.TRUE.equals(enabled);
        foreignKeyEnabled = Boolean.TRUE.equals(foreignKeyEnabled);
        viewDependencyEnabled = Boolean.TRUE.equals(viewDependencyEnabled);
        maxTraversalDepth = maxTraversalDepth > 0 ? maxTraversalDepth : 10;
        autoAnalyze = Boolean.TRUE.equals(autoAnalyze);
        maxTablesPerGraph = maxTablesPerGraph > 0 ? maxTablesPerGraph : 200;
    }

    public LineageProperties() {
        this(true, true, true, 10, true, 200);
    }
}
