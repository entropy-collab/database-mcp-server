package com.entropy.database.mcp.monitor;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Connection pool monitor with health reporting.
 * Dynamic adjustment has been removed to prevent connection pool instability.
 */
@Component
public class ConnectionPoolMonitor {

    private static final Logger log = LoggerFactory.getLogger(ConnectionPoolMonitor.class);
    private final DataSource dataSource;

    public ConnectionPoolMonitor(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Log initial pool stats on startup for monitoring.
     */
    @Bean
    public ApplicationRunner logPoolStats() {
        return args -> {
            if (dataSource instanceof HikariDataSource) {
                HikariDataSource ds = (HikariDataSource) dataSource;
                HikariPoolMXBean pool = ds.getHikariPoolMXBean();
                if (pool == null) {
                    log.debug("HikariPoolMXBean not available (test environment)");
                    return;
                }
                log.info("Connection pool initialized: active={}, idle={}, total={}",
                    pool.getActiveConnections(),
                    pool.getIdleConnections(),
                    pool.getTotalConnections());
            }
        };
    }

    /**
     * Log pool stats periodically (read-only, no dynamic adjustment).
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 300000) // 5 minutes
    public void monitorPool() {
        if (!(dataSource instanceof HikariDataSource)) return;

        HikariDataSource ds = (HikariDataSource) dataSource;
        HikariPoolMXBean pool = ds.getHikariPoolMXBean();
        if (pool == null) {
            log.debug("HikariPoolMXBean not available (test environment)");
            return;
        }

        int active = pool.getActiveConnections();
        int idle = pool.getIdleConnections();
        int total = pool.getTotalConnections();
        int waiting = pool.getThreadsAwaitingConnection();

        log.debug("Pool Stats: active={}, idle={}, total={}, waiting={}",
            active, idle, total, waiting);

        // Log warning if connections are waiting
        if (waiting > 5) {
            log.warn("High connection wait count: {} threads waiting, total pool size: {}", waiting, total);
        }
    }
}

