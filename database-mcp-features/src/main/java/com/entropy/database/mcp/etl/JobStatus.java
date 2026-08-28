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
package com.entropy.database.mcp.etl;

/**
 * Status of an ETL job.
 */
public enum JobStatus {
    /**
     * Job is queued but not started.
     */
    PENDING,

    /**
     * Job is currently running.
     */
    RUNNING,

    /**
     * Job completed successfully.
     */
    COMPLETED,

    /**
     * Job failed.
     */
    FAILED,

    /**
     * Job was stopped by user.
     */
    STOPPED
}
