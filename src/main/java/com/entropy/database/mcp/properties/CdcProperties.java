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
 * CDC (Change Data Capture) module configuration.
 */
@ConfigurationProperties(prefix = "entropy.mcp.database.cdc")
public record CdcProperties(
    boolean enabled,
    boolean enableRealtimeStreaming,
    int maxEventsPerPoll,
    long defaultPollIntervalMs,
    boolean enableMirrorTables,
    int maxMirrorTasks,
    boolean enableEventListeners
) {
    public CdcProperties() {
        this(true, false, 1000, 1000L, true, 10, true);
    }
}
