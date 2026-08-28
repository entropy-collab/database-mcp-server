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
package com.entropy.database.mcp.routing;

import com.entropy.database.mcp.byok.ByokDataSourceContext;
import com.entropy.database.mcp.byok.ByokWriteRepository;
import com.entropy.database.mcp.cache.DatabaseCache;
import com.entropy.database.mcp.domain.PaginatedQueryResult;
import com.entropy.database.mcp.domain.PlanAnalysis;
import com.entropy.database.mcp.monitor.DatabaseHealthMonitor;
import com.entropy.database.mcp.monitor.QueryStats;
import com.entropy.database.mcp.repository.DatabaseReadRepository;
import com.entropy.database.mcp.repository.ExecutionPlanRepository;
import com.entropy.database.mcp.security.QueryAuditLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ByokDatabaseFacadeTest {

    @Mock
    private ByokDataSourceContext context;

    @Mock
    private DatabaseReadRepository readRepository;

    @Mock
    private ByokWriteRepository writeRepository;

    @Mock
    private ExecutionPlanRepository executionPlanRepository;

    @Mock
    private DatabaseCache cache;

    @Mock
    private DatabaseHealthMonitor healthMonitor;

    @Mock
    private QueryStats queryStats;

    @Mock
    private QueryAuditLogger auditLogger;

    private ByokDatabaseFacade createFacade() {
        when(context.getReadRepository()).thenReturn(readRepository);
        when(context.getWriteRepository()).thenReturn(writeRepository);
        when(context.getExecutionPlanRepository()).thenReturn(executionPlanRepository);
        when(context.getCache()).thenReturn(cache);
        when(context.getHealthMonitor()).thenReturn(healthMonitor);
        when(healthMonitor.getQueryStats()).thenReturn(queryStats);
        return new ByokDatabaseFacade(context);
    }

    @Test
    void listTablesDelegatesToReadRepository() {
        ByokDatabaseFacade facade = createFacade();
        List<Map<String, Object>> expected = List.of(Map.of("name", "users"));
        when(readRepository.listTables("PUBLIC")).thenReturn(expected);

        List<Map<String, Object>> result = facade.listTables("PUBLIC", "test-connection");

        assertThat(result).isSameAs(expected);
        verify(readRepository).listTables("PUBLIC");
    }

    @Test
    void executeQueryDelegatesToReadRepository() {
        ByokDatabaseFacade facade = createFacade();
        PaginatedQueryResult expected = new PaginatedQueryResult(List.of("id"), List.of(Map.of("id", 1)), null, false);
        when(readRepository.executeQuery("SELECT 1", 100, null)).thenReturn(expected);

        PaginatedQueryResult result = facade.executeQuery("SELECT 1", 100, null, "test-connection");

        assertThat(result).isSameAs(expected);
        verify(readRepository).executeQuery("SELECT 1", 100, null);
    }

    @Test
    void explainPlanDelegatesToExecutionPlanRepository() {
        ByokDatabaseFacade facade = createFacade();
        PlanAnalysis expected = mock(PlanAnalysis.class);
        when(executionPlanRepository.analyzeExecutionPlan("SELECT 1")).thenReturn(expected);

        PlanAnalysis result = facade.explainPlan("SELECT 1", "test-connection");

        assertThat(result).isSameAs(expected);
        verify(executionPlanRepository).analyzeExecutionPlan("SELECT 1");
    }

    @Test
    void executeDdlDelegatesToWriteRepository() {
        ByokDatabaseFacade facade = createFacade();
        Map<String, Object> expected = Map.of("rows", 1);
        when(writeRepository.executeDdl("CREATE TABLE test (id INT)")).thenReturn(expected);

        Map<String, Object> result = facade.executeDdl("CREATE TABLE test (id INT)", "test-connection");

        assertThat(result).isSameAs(expected);
        verify(writeRepository).executeDdl("CREATE TABLE test (id INT)");
    }

    @Test
    void clearCacheDelegatesToCache() {
        ByokDatabaseFacade facade = createFacade();
        doNothing().when(cache).invalidateAll();

        facade.clearCache("test-connection");

        verify(cache).invalidateAll();
    }

    @Test
    void getStatisticsReturnsHealthAndCacheStats() {
        ByokDatabaseFacade facade = createFacade();
        String queryStatsSummary = "queries=10, avg_duration=5ms";
        Map<String, Object> cacheStats = Map.of("hits", 5);
        when(queryStats.toSummary()).thenReturn(queryStatsSummary);
        when(cache.getStatistics()).thenReturn(cacheStats);

        Map<String, Object> stats = facade.getStatistics("test-connection");

        assertThat(stats).containsKeys("queryStats", "cacheStats");
        assertThat(stats.get("queryStats")).isSameAs(queryStatsSummary);
        assertThat(stats.get("cacheStats")).isSameAs(cacheStats);
    }
}
