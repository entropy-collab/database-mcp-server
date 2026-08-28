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
package com.entropy.database.mcp.cdc;

import java.util.List;

/**
 * CDC service interface: change data capture, real-time mirroring, and event streaming.
 */
public interface CdcService {

    /**
     * Check whether the connected database supports native CDC features.
     */
    boolean isCdcSupported(String connection);

    /**
     * Read recent change events from the database's CDC infrastructure for a given table.
     * Returns the list of CdcChangeEvent captured since the given LSN/SCN.
     */
    List<CdcChangeEvent> readChanges(String connection, String schema, String table, long fromLsn);

    /**
     * Get the current LSN / SCN / binlog position for the database.
     * The long value is used as a watermark for subsequent CDC reads.
     */
    long getLastLsn(String connection);

    /**
     * Create a mirror/snapshot table from the source table.
     * Target table will have the same structure and initial data as source.
     */
    void createMirrorTable(String connection, String sourceSchema, String sourceTable,
                           String targetSchema, String targetTable);

    /**
     * Register a CDC subscription: which table pattern to watch and what change types to capture.
     */
    void registerSubscription(CdcSubscription subscription);

    /**
     * List all registered CDC subscriptions.
     */
    List<CdcSubscription> listSubscriptions(String connection);

    /**
     * Unregister a CDC subscription by name.
     */
    void unregisterSubscription(String subscriptionName);

    /**
     * Get the status summary of the CDC engine (events captured, lag, active subscriptions).
     */
    CdcStatus getStatus(String connection);
}
