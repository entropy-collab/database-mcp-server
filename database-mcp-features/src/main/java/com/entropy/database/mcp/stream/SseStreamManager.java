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
package com.entropy.database.mcp.stream;

import com.entropy.database.mcp.domain.PaginatedQueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Server-Sent Events (SSE) streaming manager with row-level chunking support.
 *
 * <p>Enhanced from the basic {@link SseStreamManager} to support:
 * <ul>
 *   <li><b>Chunked streaming</b> — deliver rows in configurable-sized chunks</li>
 *   <li><b>Progress callbacks</b> — notify on each chunk delivered</li>
 *   <li><b>Large result pagination</b> — split large result sets into numbered pages</li>
 *   <li><b>Streaming response builder</b> — accumulate results and produce paginated output</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * // Stream rows in chunks of 100
 * StreamingQueryResult result = streamManager.streamWithChunks(
 *     rows -> rows.subList(0, Math.min(100, rows.size())),
 *     100,
 *     columns,
 *     totalCount
 * );
 *
 * // Or use the builder for incremental accumulation
 * var builder = streamManager.newBuilder();
 * builder.accept(chunk);  // feed data incrementally
 * StreamingQueryResult result = builder.build();
 * }</pre>
 */
@Component
public class SseStreamManager {

    private static final Logger log = LoggerFactory.getLogger(SseStreamManager.class);

    /** Default chunk size for row streaming. */
    public static final int DEFAULT_CHUNK_SIZE = 100;

    /**
     * Default ceiling on how many chunks one {@code streamWithChunks} call accumulates.
     *
     * <p>The accumulated result is held in memory, so something has to bound it. Stopping at the
     * ceiling is also the only case where {@code hasMore} can honestly be true.
     */
    public static final int DEFAULT_MAX_CHUNKS = 1000;

    // ─── Chunked Streaming ──────────────────────────────────────────────────

    /**
     * Execute a query and stream results in configurable chunks, up to
     * {@link #DEFAULT_MAX_CHUNKS} chunks.
     *
     * @see #streamWithChunks(Function, int, List, Consumer, int)
     */
    public StreamingQueryResult streamWithChunks(
            Function<Integer, List<Map<String, Object>>> fetchChunk,
            int chunkSize,
            List<String> columns,
            Consumer<StreamingChunk> onComplete) {
        return streamWithChunks(fetchChunk, chunkSize, columns, onComplete, DEFAULT_MAX_CHUNKS);
    }

    /**
     * Execute a query and stream results in configurable chunks.
     *
     * <p>{@code fetchChunk} is assumed to advance a cursor: every call consumes rows. So it is
     * called only to produce chunks that end up in the result, never speculatively. {@code hasMore}
     * is true exactly when {@code maxChunks} stopped the loop, i.e. when rows may still be sitting
     * behind the cursor — it is never inferred by fetching a batch and throwing it away, which was
     * how the previous implementation lost a whole chunk of rows: the discarded batch appeared
     * neither in {@code chunks} nor in {@code totalRows}.
     *
     * <p>A fetch failure propagates. It is not "no more rows": reporting a truncated result as
     * complete is the same silent data loss with a different cause.
     *
     * @param fetchChunk a function that returns the next chunk of rows (may be partial)
     * @param chunkSize  maximum rows per chunk
     * @param columns    column names
     * @param onComplete callback invoked with each completed chunk (can be null)
     * @param maxChunks  ceiling on accumulated chunks, since the result is held in memory
     * @return the accumulated streaming result
     */
    public StreamingQueryResult streamWithChunks(
            Function<Integer, List<Map<String, Object>>> fetchChunk,
            int chunkSize,
            List<String> columns,
            Consumer<StreamingChunk> onComplete,
            int maxChunks) {
        if (chunkSize <= 0) chunkSize = DEFAULT_CHUNK_SIZE;
        int chunkCeiling = maxChunks > 0 ? maxChunks : DEFAULT_MAX_CHUNKS;
        List<StreamingChunk> chunks = new ArrayList<>();
        int totalRows = 0;
        int pageNum = 0;
        boolean stoppedAtCeiling = false;

        while (true) {
            List<Map<String, Object>> chunk = fetchChunk.apply(chunkSize);
            if (chunk == null || chunk.isEmpty()) break;

            StreamingChunk sc = new StreamingChunk(pageNum++, chunk, totalRows);
            chunks.add(sc);
            totalRows += chunk.size();

            if (onComplete != null) {
                onComplete.accept(sc);
            }

            // A short chunk means the source is exhausted. A full one says nothing either way, so
            // the loop keeps going until the source says it is done or the ceiling stops us.
            if (chunk.size() < chunkSize) break;
            if (chunks.size() >= chunkCeiling) {
                stoppedAtCeiling = true;
                log.warn("Stopped streaming at the {}-chunk ceiling after {} rows; more rows may remain",
                        chunkCeiling, totalRows);
                break;
            }
        }

        return new StreamingQueryResult(columns, chunks, totalRows, stoppedAtCeiling);
    }

    /**
     * Stream an already-collected list in fixed-size chunks.
     */
    public StreamingQueryResult streamAsChunks(
            List<Map<String, Object>> allRows,
            List<String> columns,
            int chunkSize,
            Consumer<StreamingChunk> onComplete) {
        if (chunkSize <= 0) chunkSize = DEFAULT_CHUNK_SIZE;
        List<StreamingChunk> chunks = new ArrayList<>();
        int totalRows = 0;
        int pageNum = 0;

        for (int i = 0; i < allRows.size(); i += chunkSize) {
            List<Map<String, Object>> slice = allRows.subList(i, Math.min(i + chunkSize, allRows.size()));
            StreamingChunk sc = new StreamingChunk(pageNum++, slice, totalRows);
            chunks.add(sc);
            totalRows += slice.size();
            if (onComplete != null) onComplete.accept(sc);
        }

        return new StreamingQueryResult(columns, chunks, totalRows, false);
    }

    /**
     * Execute with progress callbacks (original API compatibility).
     */
    public <T> T executeWithProgress(Consumer<ProgressEvent> onProgress, QueryExecutor<T> executor) {
        onProgress.accept(ProgressEvent.start());
        try {
            T result = executor.execute();
            onProgress.accept(ProgressEvent.end(result));
            return result;
        } catch (Exception e) {
            onProgress.accept(ProgressEvent.error("Stream execution failed"));
            throw new IllegalStateException("Query failed", e);
        }
    }

    // ─── Stream Builder (incremental accumulation) ──────────────────────────

    /**
     * Create a new streaming result builder for incremental accumulation.
     */
    public StreamBuilder newBuilder() {
        return new StreamBuilder();
    }

    // ─── Nested types ───────────────────────────────────────────────────────

    /**
     * A chunk of rows delivered during streaming.
     */
    public record StreamingChunk(
            int pageNumber,
            List<Map<String, Object>> rows,
            int cumulativeRowOffset
    ) {
        public int rowCount() {
            return rows.size();
        }

        public int nextPageNumber() {
            return pageNumber + 1;
        }
    }

    /**
     * Accumulated streaming query result containing all chunks.
     */
    public record StreamingQueryResult(
            List<String> columns,
            List<StreamingChunk> chunks,
            int totalRows,
            boolean hasMore
    ) {
        public int chunkCount() {
            return chunks.size();
        }

        public List<Map<String, Object>> allRows() {
            return chunks.stream()
                    .flatMap(c -> c.rows().stream())
                    .toList();
        }

        public PaginatedQueryResult toPaginated() {
            return PaginatedQueryResult.from(allRows(), null, hasMore);
        }
    }

    /**
     * Builder for incrementally accumulating streaming results.
     */
    public static class StreamBuilder {
        private List<String> columns;
        private final List<StreamingChunk> chunks = new ArrayList<>();
        private int cumulativeOffset = 0;

        public StreamBuilder columns(List<String> columns) {
            this.columns = columns;
            return this;
        }

        public StreamBuilder accept(List<Map<String, Object>> chunk) {
            return accept(chunk, null);
        }

        public StreamBuilder accept(List<Map<String, Object>> chunk, Consumer<StreamingChunk> callback) {
            if (chunk == null || chunk.isEmpty()) return this;
            if (columns == null && !chunk.isEmpty()) {
                columns = List.copyOf(chunk.get(0).keySet());
            }
            StreamingChunk sc = new StreamingChunk(chunks.size(), chunk, cumulativeOffset);
            chunks.add(sc);
            cumulativeOffset += chunk.size();
            if (callback != null) callback.accept(sc);
            return this;
        }

        public StreamingQueryResult build() {
            return new StreamingQueryResult(columns != null ? columns : List.of(), chunks, cumulativeOffset, false);
        }
    }

    // ─── Legacy types (for compatibility) ───────────────────────────────────

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
            Map<String, String> err = Map.of("message", message);
            return new ProgressEvent("ERROR", err, System.currentTimeMillis());
        }
    }

    @FunctionalInterface
    public interface QueryExecutor<T> {
        T execute() throws Exception;
    }
}
