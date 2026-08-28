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

import java.time.Instant;

/**
 * A single CDC change event captured from the source database.
 */
public record CdcChangeEvent(
    String connection,
    String schema,
    String table,
    CdcChangeType changeType,
    Instant changeTime,
    String primaryKeys,
    String beforeJson,
    String afterJson,
    Long transactionId,
    CdcEventStatus status
) {
    public CdcChangeEvent {
        if (status == null) status = CdcEventStatus.PROCESSED;
    }

    public boolean isInsert() { return changeType == CdcChangeType.INSERT; }
    public boolean isUpdate() { return changeType == CdcChangeType.UPDATE; }
    public boolean isDelete() { return changeType == CdcChangeType.DELETE; }
}
