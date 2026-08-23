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
package com.entropy.database.mcp.aop;

import com.entropy.database.mcp.aop.ConnectionArgExtractor;
import com.entropy.database.mcp.monitor.DatabaseHealthMonitor;
import com.entropy.database.mcp.monitor.McpMetricsCollector;
import com.entropy.database.mcp.security.QueryAuditLogger;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * AOP aspect for unified performance timing and metrics collection.
 * Eliminates duplicated timing boilerplate across repositories and facades.
 */
@Aspect
@Component
public class PerformanceTimingAspect {

    private static final Logger log = LoggerFactory.getLogger(PerformanceTimingAspect.class);

    private final DatabaseHealthMonitor healthMonitor;
    private final McpMetricsCollector metricsCollector;
    private final QueryAuditLogger auditLogger;

    public PerformanceTimingAspect(DatabaseHealthMonitor healthMonitor,
                                   McpMetricsCollector metricsCollector,
                                   QueryAuditLogger auditLogger) {
        this.healthMonitor = healthMonitor;
        this.metricsCollector = metricsCollector;
        this.auditLogger = auditLogger;
    }

    @Around("execution(* com.entropy.database.mcp.facade.RoutingDatabaseFacade.*(..)) || " +
            "execution(* com.entropy.database.mcp.facade.ByokDatabaseFacade.*(..)) || " +
            "execution(* com.entropy.database.mcp.repository.ExecutionPlanRepositoryImpl.*(..)) || " +
            "execution(* com.entropy.database.mcp.byok.DynamicDataSourceManagerImpl.*(..)) || " +
            "execution(* com.entropy.database.mcp.etl.JobExecutionEngine.*(..))")
    public Object timeDatabaseOperation(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.currentTimeMillis();
        String toolName = pjp.getSignature().getName();
        String sql = extractSql(pjp);
        Optional<String> connectionOpt = ConnectionArgExtractor.extractConnectionName(pjp.getArgs());
        String connectionKey = connectionOpt.orElse(null);

        try {
            Object result = pjp.proceed();
            long duration = System.currentTimeMillis() - start;
            recordResult(toolName, sql, connectionKey, duration, extractRowCount(result), true);
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            recordResult(toolName, sql, connectionKey, duration, 0, false, e.getMessage());
            throw e;
        }
    }

    private void recordResult(String toolName, String sql, String connectionKey,
                              long duration, int rows, boolean success) {
        recordResult(toolName, sql, connectionKey, duration, rows, success, null);
    }

    private void recordResult(String toolName, String sql, String connectionKey,
                              long duration, int rows, boolean success, String errorMessage) {
        healthMonitor.recordQuery(duration, rows, success);
        try {
            auditLogger.log(toolName, sql, rows, duration, success, errorMessage,
                    connectionKey != null ? connectionKey : "primary");
        } catch (Exception e) {
            log.warn("Failed to record audit log for {}", toolName, e);
        }
        metricsCollector.recordToolExecution(toolName, duration);
    }

    private String extractSql(ProceedingJoinPoint pjp) {
        Object[] args = pjp.getArgs();
        if (args != null && args.length > 0 && args[0] instanceof String s) {
            return s;
        }
        return "";
    }

    private int extractRowCount(Object result) {
        if (result instanceof java.util.Map<?, ?> map) {
            Object rows = map.get("rows");
            if (rows instanceof java.util.Collection<?> col) {
                return col.size();
            }
            Object affected = map.get("affectedRows");
            if (affected instanceof Integer i) {
                return i;
            }
        } else if (result instanceof java.util.Collection<?> col) {
            return col.size();
        }
        return 0;
    }
}
