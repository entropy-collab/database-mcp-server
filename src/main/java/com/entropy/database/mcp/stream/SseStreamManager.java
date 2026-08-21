/*
 * Copyright 2024-2026 Entropy Pty Ltd.
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
package com.entropy.database.mcp.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Server-Sent Events (SSE) streaming manager.
 * Supports row-level progress推送 for long-running queries.
 */
@Component
public class SseStreamManager {

    private static final Logger log = LoggerFactory.getLogger(SseStreamManager.class);

    /**
     * Execute a query with SSE progress streaming.
     * The Consumer receives progress events: PROGRESS_START, ROW_CHUNK, PROGRESS_END.
     */
    public <T> T executeWithProgress(Consumer<ProgressEvent> onProgress, QueryExecutor<T> executor) {
        onProgress.accept(ProgressEvent.start());
        try {
            T result = executor.execute();
            onProgress.accept(ProgressEvent.end(result));
            return result;
        } catch (Exception e) {
            onProgress.accept(ProgressEvent.error(e.getMessage()));
            throw new RuntimeException("Query failed: " + e.getMessage(), e);
        }
    }

    // ─── Nested types ─────────────────────────────────────────────────────

    public record ProgressEvent(
        String type,
        Object data,
        long timestamp
    ) {
        public static ProgressEvent start() {
            return new ProgressEvent("START", null, System.currentTimeMillis());
        }
        public static ProgressEvent end(Object data) {
            return new ProgressEvent("PROGRESS_END", data, System.currentTimeMillis());
        }
        public static ProgressEvent error(String message) {
            Map<String, String> err = new HashMap<>();
            err.put("message", message);
            return new ProgressEvent("ERROR", err, System.currentTimeMillis());
        }
    }

    @FunctionalInterface
    public interface QueryExecutor<T> {
        T execute() throws Exception;
    }
}
