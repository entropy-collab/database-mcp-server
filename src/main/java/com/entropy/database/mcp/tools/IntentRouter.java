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

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图驱动的白名单过滤路由器。
 *
 * <p>根据用户输入的自然语言意图，通过关键词匹配返回推荐使用的工具名称列表。
 * 支持 QUERY、SCHEMA、ANALYSIS、DDL、BACKUP、ETL、LINEAGE、CROSS_DB、HEALTH、AUDIT、
 * ADMIN、CDC、QUALITY、SESSION、ORACLE_SESSION、EXPORT 等意图类别。
 */
@Component
public class IntentRouter {

    private static final Map<String, List<String>> INTENT_KEYWORDS;

    private static final Map<String, List<String>> INTENT_TOOLS;

    static {
        INTENT_KEYWORDS = new HashMap<>();
        INTENT_KEYWORDS.put("QUERY", List.of(
                "query", "select", "查询", "查询一下", "搜一下", "查找", "检索"
        ));
        INTENT_KEYWORDS.put("SCHEMA", List.of(
                "table", "schema", "表", "字段", "结构", "列", "describe", "list",
                "查看表", "查看schema", "表结构", "字段列表"
        ));
        INTENT_KEYWORDS.put("ANALYSIS", List.of(
                "explain", "analyze", "分析", "优化", "索引建议", "重写", "性能",
                "风险", "评估", "plan", "plan analysis"
        ));
        INTENT_KEYWORDS.put("DDL", List.of(
                "ddl", "create", "alter", "drop", "truncate", "建表", "修改表",
                "删除表", "执行ddl", "dml"
        ));
        INTENT_KEYWORDS.put("BACKUP", List.of(
                "backup", "restore", "备份", "恢复", "quick restore", "list backup", "snapshot"
        ));
        INTENT_KEYWORDS.put("ETL", List.of(
                "etl", "insert", "transform", "upsert", "load", "数据加载",
                "批量插入", "转换", "同步", "validate data"
        ));
        INTENT_KEYWORDS.put("LINEAGE", List.of(
                "lineage", "upstream", "downstream", "impact", "血缘", "上游",
                "下游", "影响分析", "依赖"
        ));
        INTENT_KEYWORDS.put("CROSS_DB", List.of(
                "cross db", "cross-database", "remote table", "dblink", "db link",
                "跨库", "远程表", "dblink"
        ));
        INTENT_KEYWORDS.put("HEALTH", List.of(
                "health", "session", "lock", "blocking", "tablespace", "监控",
                "会话", "锁", "阻塞", "健康"
        ));
        INTENT_KEYWORDS.put("AUDIT", List.of(
                "audit", "slow query", "sql pattern", "审计", "慢查询", "sql模式", "日志"
        ));
        INTENT_KEYWORDS.put("ADMIN", List.of(
                "admin", "connection", "pool", "cache", "statistics", "配置",
                "连接", "缓存", "统计"
        ));
        INTENT_KEYWORDS.put("CDC", List.of(
                "cdc", "change data", "捕获", "增量", "同步数据", "lsn"
        ));
        INTENT_KEYWORDS.put("QUALITY", List.of(
                "quality", "数据质量", "质量规则", "质量报告"
        ));
        INTENT_KEYWORDS.put("SESSION", List.of(
                "session store", "session get", "session keys", "session remove",
                "session purge", "会话管理", "session info"
        ));
        INTENT_KEYWORDS.put("ORACLE_SESSION", List.of(
                "kill session", "终止会话", "杀会话"
        ));
        INTENT_KEYWORDS.put("EXPORT", List.of(
                "export", "导出", "csv", "json"
        ));

        INTENT_TOOLS = new HashMap<>();
        INTENT_TOOLS.put("QUERY", List.of("executeQuery", "batchQuery", "executeSqlTemplate", "exportCsv", "exportJson"));
        INTENT_TOOLS.put("SCHEMA", List.of("listTables", "searchTables", "describeTable", "listIndexes", "listViews", "listSchemas", "listSequences"));
        INTENT_TOOLS.put("ANALYSIS", List.of("explainPlan", "assessQueryRisk", "analyzeQuery", "recommendIndexes", "suggestRewrites", "interpretPlan"));
        INTENT_TOOLS.put("DDL", List.of("executeDdl", "executeDdlRemote", "executeDdlBatch", "validateDdl"));
        INTENT_TOOLS.put("BACKUP", List.of("backupTable", "backupSchema", "restoreBackup", "quickRestore", "listBackups", "getBackup", "deleteBackup", "cleanupBackups"));
        INTENT_TOOLS.put("ETL", List.of("createNamedConnection", "insertData", "insertQueryResult", "transformAndInsert", "upsertData", "validateDataQuality", "exportQueryToTable", "submitEtlJob", "getEtlJobStatus", "listEtlJobs", "stopEtlJob"));
        INTENT_TOOLS.put("LINEAGE", List.of("getUpstream", "getDownstream", "analyzeLineage", "getImpactAnalysis", "exportMermaid", "exportDot", "listAllEdges"));
        INTENT_TOOLS.put("CROSS_DB", List.of("queryCrossDatabaseJoin", "listRemoteTables", "describeRemoteTable", "queryComplexCrossDatabaseAnalytics", "getCrossDatabaseTemplates", "listDatabases", "createDbLink", "dropDbLink", "testDbLink"));
        INTENT_TOOLS.put("HEALTH", List.of("checkHealth", "listActiveSessions", "showLocks", "showBlockingTree", "listTablespaces", "listDataFiles", "estimateTableSize", "listInvalidObjects", "gatherTableStats", "showIndexStatus", "flashbackQuery", "showUndoUsage", "listCurrentPrivileges", "listGrants"));
        INTENT_TOOLS.put("AUDIT", List.of("getAuditLogs", "getSlowQueries", "getSqlPatternStats", "getDataAccessReport", "getProtectionReport", "getAuditMetrics"));
        INTENT_TOOLS.put("ADMIN", List.of("listConnections", "describeConnection", "getConnectionCount", "getPoolStats", "getPoolStatsForConnection", "clearCache", "getStatistics", "getMetrics", "getOptimizerConfig", "getCatalogConfig", "getLineageConfig", "getCdcConfig"));
        INTENT_TOOLS.put("CDC", List.of("checkCdcSupport", "readChanges", "getCurrentLsn", "createMirrorTable", "registerSubscription", "listSubscriptions", "unregisterSubscription", "getCdcStatus", "getCdcConfig"));
        INTENT_TOOLS.put("QUALITY", List.of("checkTableQuality", "listQualityRuleTemplates", "getQualityAlertSummary"));
        INTENT_TOOLS.put("SESSION", List.of("sessionStore", "sessionGet", "sessionKeys", "sessionRemove", "sessionPurge", "getSessionInfo"));
        INTENT_TOOLS.put("ORACLE_SESSION", List.of("killSession"));
        INTENT_TOOLS.put("EXPORT", List.of("exportCsv", "exportJson"));
    }

    /**
     * 根据用户输入路由意图并返回推荐工具列表。
     *
     * @param userInput 用户的自然语言查询或指令
     * @return 包含 intent、recommendedTools、confidence 的结果 Map
     */
    public Map<String, Object> route(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return Map.of("intent", "UNKNOWN", "recommendedTools", List.of(), "confidence", "none");
        }

        String lowerInput = userInput.toLowerCase();
        String matchedIntent = null;
        int maxScore = 0;

        for (Map.Entry<String, List<String>> entry : INTENT_KEYWORDS.entrySet()) {
            int score = 0;
            for (String keyword : entry.getValue()) {
                if (lowerInput.contains(keyword.toLowerCase())) {
                    score += keyword.length(); // 较长的关键词权重更高
                }
            }
            if (score > maxScore) {
                maxScore = score;
                matchedIntent = entry.getKey();
            }
        }

        String intent = matchedIntent != null ? matchedIntent : "UNKNOWN";
        List<String> tools = matchedIntent != null
                ? INTENT_TOOLS.get(matchedIntent)
                : List.of();

        String confidence;
        if (matchedIntent == null) {
            confidence = "none";
        } else if (maxScore >= 10) {
            confidence = "high";
        } else if (maxScore >= 5) {
            confidence = "medium";
        } else {
            confidence = "low";
        }

        return Map.of(
                "intent", intent,
                "recommendedTools", tools,
                "confidence", confidence
        );
    }
}
