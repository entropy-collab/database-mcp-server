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

import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.jmx.annotation.JmxEndpoint;
import org.springframework.stereotype.Component;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JMX MBean exposing real-time HikariCP pool statistics.
 * Accessible via JConsole, VisualVM, or {@code jcmd}.
 */
@Component
public class PoolStatsMBean implements PoolStatsMBeanMxBean {

    private static final Logger log = LoggerFactory.getLogger(PoolStatsMBean.class);
    private static final String OBJECT_NAME = "com.entropy.database.mcp:type=PoolStats";

    private final DynamicDataSourceManager dataSourceManager;
    private final McpMetricsCollector metricsCollector;
    private volatile ObjectName jmxObjectName;

    public PoolStatsMBean(DynamicDataSourceManager dataSourceManager,
                          McpMetricsCollector metricsCollector) {
        this.dataSourceManager = dataSourceManager;
        this.metricsCollector = metricsCollector;
        registerJmx();
    }

    @SuppressWarnings("unchecked")
    private void registerJmx() {
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            if (mbs.isRegistered(jmxObjectName)) {
                mbs.unregisterMBean(jmxObjectName);
            }
            jmxObjectName = new ObjectName(OBJECT_NAME);
            mbs.registerMBean(this, jmxObjectName);
            log.info("PoolStatsMBean registered at {}", OBJECT_NAME);
        } catch (Exception e) {
            log.warn("Failed to register PoolStatsMBean: {}", e.getMessage(), e);
        }
    }

    @Override
    public Map<String, Object> getPoolStats() {
        Map<String, HikariPoolStats> stats = dataSourceManager.getPoolStats();
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, HikariPoolStats> entry : stats.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toMap());
        }
        return result;
    }

    @Override
    public Map<String, Object> getMetrics() {
        return metricsCollector.getMetrics();
    }

    @Override
    public String getRegisteredObjectName() {
        return jmxObjectName != null ? jmxObjectName.toString() : "Not registered";
    }
}
