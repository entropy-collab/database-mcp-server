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
package com.entropy.database.mcp.tools;

import com.entropy.database.mcp.facade.RoutingDatabaseFacade;
import com.entropy.database.mcp.byok.DynamicDataSourceManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for administration MCP tools.
 */
@SpringBootTest(properties = {
        "entropy.mcp.database.ddl.allowed=true",
        "entropy.mcp.gateway.enabled=false"
})
class AdminToolsTest {

    @Autowired
    private OracleSessionTools oracleSessionTools;

    @Autowired
    private DatabaseHealthTools databaseHealthTools;

    @Autowired
    private RoutingDatabaseFacade routingFacade;

    @Autowired
    private DynamicDataSourceManager dataSourceManager;

    @Autowired
    private Environment environment;

    // ─── killSession validation tests ────────────────────────────────────────

    @Test
    void testKillSessionBlankSessionId() {
        Map<String, Object> result = oracleSessionTools.killSession("", null, "primary");
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("error")).isEqualTo("sessionId cannot be blank");
    }

    @Test
    void testKillSessionNullSessionId() {
        Map<String, Object> result = oracleSessionTools.killSession(null, null, "primary");
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("error")).isEqualTo("sessionId cannot be blank");
    }

    @Test
    void testKillSessionInvalidFormat() {
        Map<String, Object> result = oracleSessionTools.killSession("123", null, "primary");
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("error")).isEqualTo("sessionId must be in format 'sid,serial#' (e.g. '123,4567')");
    }

    @Test
    void testKillSessionInvalidFormatLetters() {
        Map<String, Object> result = oracleSessionTools.killSession("abc,def", null, "primary");
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("error")).isEqualTo("sessionId must be in format 'sid,serial#' (e.g. '123,4567')");
    }

    @Test
    void testKillSessionInvalidMode() {
        Map<String, Object> result = oracleSessionTools.killSession("123,4567", "INVALID", "primary");
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("error")).isEqualTo("mode must be IMMEDIATE or POST_TRANSACTION");
    }

    @Test
    void testKillSessionValidFormat() {
        // This will fail at execution because H2 doesn't support ALTER SYSTEM,
        // but validation should pass and return an error from the database
        Map<String, Object> result = oracleSessionTools.killSession("123,4567", null, "primary");
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("sessionId")).isEqualTo("123,4567");
        assertThat(result.get("mode")).isEqualTo("IMMEDIATE");
    }

    @Test
    void testKillSessionPostTransactionMode() {
        Map<String, Object> result = oracleSessionTools.killSession("123,4567", "POST_TRANSACTION", "primary");
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("sessionId")).isEqualTo("123,4567");
        assertThat(result.get("mode")).isEqualTo("POST_TRANSACTION");
    }

    @Test
    void testKillSessionLowerCaseMode() {
        Map<String, Object> result = oracleSessionTools.killSession("123,4567", "immediate", "primary");
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("sessionId")).isEqualTo("123,4567");
        assertThat(result.get("mode")).isEqualTo("IMMEDIATE");
    }

    // ─── P0: Diagnostic Tools ────────────────────────────────────────────────

    @Test
    void testListActiveSessions() {
        Map<String, Object> result = databaseHealthTools.listActiveSessions("primary");
        assertThat(result.get("success")).isInstanceOf(Boolean.class);
        if ((boolean) result.getOrDefault("success", false)) {
            assertThat(result.get("dialect")).isEqualTo("generic");
            assertThat(result.get("rows")).isInstanceOf(List.class);
        }
    }

    @Test
    void testShowLocks() {
        Map<String, Object> result = databaseHealthTools.showLocks("primary");
        assertThat(result.get("success")).isInstanceOf(Boolean.class);
        if ((boolean) result.getOrDefault("success", false)) {
            assertThat(result.get("dialect")).isEqualTo("generic");
            assertThat(result.get("rows")).isInstanceOf(List.class);
        }
    }

    @Test
    void testShowBlockingTree() {
        Map<String, Object> result = databaseHealthTools.showBlockingTree("primary");
        assertThat(result.get("success")).isInstanceOf(Boolean.class);
        if ((boolean) result.getOrDefault("success", false)) {
            assertThat(result.get("dialect")).isEqualTo("generic");
            assertThat(result.get("rows")).isInstanceOf(List.class);
        }
    }

    // ─── P1: Storage and Capacity Management ─────────────────────────────────

    @Test
    void testListTablespaces() {
        Map<String, Object> result = databaseHealthTools.listTablespaces("primary");
        assertThat(result.get("success")).isInstanceOf(Boolean.class);
        if ((boolean) result.getOrDefault("success", false)) {
            assertThat(result.get("dialect")).isEqualTo("generic");
            assertThat(result.get("rows")).isInstanceOf(List.class);
        }
    }

    @Test
    void testListDataFiles() {
        Map<String, Object> result = databaseHealthTools.listDataFiles("primary");
        assertThat(result.get("success")).isInstanceOf(Boolean.class);
        if ((boolean) result.getOrDefault("success", false)) {
            assertThat(result.get("dialect")).isEqualTo("generic");
            assertThat(result.get("rows")).isInstanceOf(List.class);
        }
    }

    @Test
    void testEstimateTableSize() {
        Map<String, Object> result = databaseHealthTools.estimateTableSize("MY_TABLE", null, "primary");
        assertThat(result.get("success")).isInstanceOf(Boolean.class);
        if ((boolean) result.getOrDefault("success", false)) {
            assertThat(result.get("tableName")).isEqualTo("MY_TABLE");
            assertThat(result.get("schema")).isEqualTo(null);
            assertThat(result.get("dialect")).isEqualTo("generic");
            assertThat(result.get("rows")).isInstanceOf(List.class);
        }
    }

    @Test
    void testEstimateTableSizeBlankTableName() {
        Map<String, Object> result = databaseHealthTools.estimateTableSize("", null, "primary");
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("error")).isEqualTo("tableName cannot be blank");
    }

    @Test
    void testEstimateTableSizeNullTableName() {
        Map<String, Object> result = databaseHealthTools.estimateTableSize(null, null, "primary");
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("error")).isEqualTo("tableName cannot be blank");
    }

    // ─── P2: Object Health and Statistics ────────────────────────────────────

    @Test
    void testListInvalidObjects() {
        Map<String, Object> result = databaseHealthTools.listInvalidObjects(null, "primary");
        assertThat(result.get("success")).isInstanceOf(Boolean.class);
        if ((boolean) result.getOrDefault("success", false)) {
            assertThat(result.get("dialect")).isEqualTo("generic");
            assertThat(result.get("rows")).isInstanceOf(List.class);
        }
    }

    @Test
    void testGatherTableStats() {
        Map<String, Object> result = databaseHealthTools.gatherTableStats("MY_TABLE", null, "primary");
        assertThat(result.get("success")).isInstanceOf(Boolean.class);
        if ((boolean) result.getOrDefault("success", false)) {
            assertThat(result.get("tableName")).isEqualTo("MY_TABLE");
            assertThat(result.get("schema")).isEqualTo(null);
            assertThat(result.get("dialect")).isEqualTo("generic");
            assertThat(result.get("rows")).isInstanceOf(List.class);
        }
    }

    @Test
    void testGatherTableStatsBlankTableName() {
        Map<String, Object> result = databaseHealthTools.gatherTableStats("", null, "primary");
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("error")).isEqualTo("tableName cannot be blank");
    }

    @Test
    void testGatherTableStatsNullTableName() {
        Map<String, Object> result = databaseHealthTools.gatherTableStats(null, null, "primary");
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("error")).isEqualTo("tableName cannot be blank");
    }

    @Test
    void testShowIndexStatus() {
        Map<String, Object> result = databaseHealthTools.showIndexStatus(null, null, "primary");
        assertThat(result.get("success")).isInstanceOf(Boolean.class);
        if ((boolean) result.getOrDefault("success", false)) {
            assertThat(result.get("dialect")).isEqualTo("generic");
            assertThat(result.get("rows")).isInstanceOf(List.class);
        }
    }

    // ─── P3: Flashback and Undo Management ───────────────────────────────────

    @Test
    void testFlashbackQuery() {
        Map<String, Object> result = databaseHealthTools.flashbackQuery("MY_TABLE", "2024-01-01 00:00:00", null, "primary");
        assertThat(result.get("success")).isInstanceOf(Boolean.class);
        if ((boolean) result.getOrDefault("success", false)) {
            assertThat(result.get("tableName")).isEqualTo("MY_TABLE");
            assertThat(result.get("timestamp")).isEqualTo("2024-01-01 00:00:00");
            assertThat(result.get("schema")).isEqualTo(null);
            assertThat(result.get("dialect")).isEqualTo("generic");
            assertThat(result.get("rows")).isInstanceOf(List.class);
        }
    }

    @Test
    void testFlashbackQueryBlankTableName() {
        Map<String, Object> result = databaseHealthTools.flashbackQuery("", "2024-01-01", null, "primary");
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("error")).isEqualTo("tableName cannot be blank");
    }

    @Test
    void testFlashbackQueryNullTableName() {
        Map<String, Object> result = databaseHealthTools.flashbackQuery(null, "2024-01-01", null, "primary");
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("error")).isEqualTo("tableName cannot be blank");
    }

    @Test
    void testFlashbackQueryBlankTimestamp() {
        Map<String, Object> result = databaseHealthTools.flashbackQuery("MY_TABLE", "", null, "primary");
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("error")).isEqualTo("timestamp cannot be blank");
    }

    @Test
    void testFlashbackQueryNullTimestamp() {
        Map<String, Object> result = databaseHealthTools.flashbackQuery("MY_TABLE", null, null, "primary");
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("error")).isEqualTo("timestamp cannot be blank");
    }

    @Test
    void testShowUndoUsage() {
        Map<String, Object> result = databaseHealthTools.showUndoUsage("primary");
        assertThat(result.get("success")).isInstanceOf(Boolean.class);
        if ((boolean) result.getOrDefault("success", false)) {
            assertThat(result.get("dialect")).isEqualTo("generic");
            assertThat(result.get("rows")).isInstanceOf(List.class);
        }
    }

    // ─── P3: User and Privilege Audit ────────────────────────────────────────

    @Test
    void testListCurrentPrivileges() {
        Map<String, Object> result = databaseHealthTools.listCurrentPrivileges("primary");
        assertThat(result.get("success")).isInstanceOf(Boolean.class);
        if ((boolean) result.getOrDefault("success", false)) {
            assertThat(result.get("dialect")).isEqualTo("generic");
            assertThat(result.get("rows")).isInstanceOf(List.class);
        }
    }

    @Test
    void testListGrants() {
        Map<String, Object> result = databaseHealthTools.listGrants("TEST_USER", "primary");
        assertThat(result.get("success")).isInstanceOf(Boolean.class);
        if ((boolean) result.getOrDefault("success", false)) {
            assertThat(result.get("userName")).isEqualTo("TEST_USER");
            assertThat(result.get("dialect")).isEqualTo("generic");
            assertThat(result.get("rows")).isInstanceOf(List.class);
        }
    }

    @Test
    void testListGrantsBlankUserName() {
        Map<String, Object> result = databaseHealthTools.listGrants("", "primary");
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("error")).isEqualTo("userName cannot be blank");
    }

    @Test
    void testListGrantsNullUserName() {
        Map<String, Object> result = databaseHealthTools.listGrants(null, "primary");
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("error")).isEqualTo("userName cannot be blank");
    }
}
