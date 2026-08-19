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

@ConfigurationProperties(prefix = "entropy.mcp.database")
public record DatabaseProperties(
    boolean enabled,
    String dialect,

    QueryProperties query,

    DdlProperties ddl
) {
    public DatabaseProperties {
        if (dialect == null || dialect.isBlank()) {
            dialect = "oracle";
        }
        if (query == null) {
            query = new QueryProperties(100, 30, true, 10000);
        }
        if (ddl == null) {
            ddl = new DdlProperties(false);
        }
    }

    public record QueryProperties(
        int maxRows,
        int timeoutSeconds,
        boolean cacheEnabled,
        int maxResultRows
    ) {
        public QueryProperties {
            maxRows = maxRows > 0 ? maxRows : 100;
            timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 30;
            cacheEnabled = cacheEnabled;
            maxResultRows = maxResultRows > 0 ? maxResultRows : 10000;
        }
    }

    public record DdlProperties(boolean allowed) {
        public DdlProperties {
            allowed = Boolean.TRUE.equals(allowed);
        }
    }
}
