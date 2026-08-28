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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards {@link SseStreamManager#streamWithChunks} against losing rows.
 *
 * <p>{@code fetchChunk} advances a cursor, so calling it is not free: it consumes rows. The
 * previous {@code hasMore} implementation called it one extra time and threw the returned batch
 * away, so those rows appeared in neither {@code chunks} nor {@code totalRows} — a silent partial
 * result. It also treated a fetch failure as "no more rows", which reported a truncated stream as
 * complete. Both are pinned here by comparing the streamed rows against the full source.
 */
class SseStreamManagerTest {

    private final SseStreamManager manager = new SseStreamManager();

    @Test
    void streamsEveryRowWhenTheLastChunkIsPartial() {
        List<Map<String, Object>> source = rows(25);
        Cursor cursor = new Cursor(source);

        SseStreamManager.StreamingQueryResult result =
                manager.streamWithChunks(cursor, 10, List.of("id"), null);

        assertThat(result.totalRows()).isEqualTo(25);
        assertThat(result.allRows()).isEqualTo(source);
        assertThat(result.chunkCount()).isEqualTo(3);
        assertThat(result.hasMore()).isFalse();
        // 3 chunks, 3 calls: no speculative extra fetch.
        assertThat(cursor.calls()).isEqualTo(3);
    }

    @Test
    void streamsEveryRowWhenTheSourceIsAnExactMultipleOfTheChunkSize() {
        List<Map<String, Object>> source = rows(20);
        Cursor cursor = new Cursor(source);

        SseStreamManager.StreamingQueryResult result =
                manager.streamWithChunks(cursor, 10, List.of("id"), null);

        assertThat(result.totalRows()).isEqualTo(20);
        assertThat(result.allRows()).isEqualTo(source);
        // The source was drained, so nothing is left behind the cursor. The third call returned an
        // empty batch, which is how exhaustion is observed — no rows were fetched and discarded.
        assertThat(result.hasMore()).isFalse();
        assertThat(cursor.calls()).isEqualTo(3);
    }

    @Test
    void reportsMoreRowsOnlyWhenTheChunkCeilingStoppedTheStream() {
        List<Map<String, Object>> source = rows(50);
        Cursor cursor = new Cursor(source);

        SseStreamManager.StreamingQueryResult result =
                manager.streamWithChunks(cursor, 10, List.of("id"), null, 2);

        // Stopped at the ceiling: the rows that were read are all present, and the caller is told
        // there are more rather than being handed a truncated result labelled complete.
        assertThat(result.totalRows()).isEqualTo(20);
        assertThat(result.allRows()).isEqualTo(source.subList(0, 20));
        assertThat(result.hasMore()).isTrue();
        assertThat(cursor.calls()).isEqualTo(2);
    }

    @Test
    void reportsNoMoreRowsForAnEmptySource() {
        Cursor cursor = new Cursor(List.of());

        SseStreamManager.StreamingQueryResult result =
                manager.streamWithChunks(cursor, 10, List.of("id"), null);

        assertThat(result.totalRows()).isZero();
        assertThat(result.chunkCount()).isZero();
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void deliversEveryChunkToTheProgressCallback() {
        List<Map<String, Object>> source = rows(12);
        List<Map<String, Object>> delivered = new ArrayList<>();

        SseStreamManager.StreamingQueryResult result = manager.streamWithChunks(
                new Cursor(source), 5, List.of("id"), chunk -> delivered.addAll(chunk.rows()));

        assertThat(delivered).isEqualTo(source);
        assertThat(result.totalRows()).isEqualTo(12);
    }

    @Test
    void propagatesAFetchFailureInsteadOfReportingCompletion() {
        Function<Integer, List<Map<String, Object>>> failing = size -> {
            throw new IllegalStateException("cursor died");
        };

        assertThatThrownBy(() -> manager.streamWithChunks(failing, 10, List.of("id"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("cursor died");
    }

    @Test
    void streamAsChunksSplitsWithoutLosingRows() {
        List<Map<String, Object>> source = rows(7);

        SseStreamManager.StreamingQueryResult result =
                manager.streamAsChunks(source, List.of("id"), 3, null);

        assertThat(result.totalRows()).isEqualTo(7);
        assertThat(result.chunkCount()).isEqualTo(3);
        assertThat(result.allRows()).isEqualTo(source);
    }

    private static List<Map<String, Object>> rows(int count) {
        List<Map<String, Object>> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(Map.of("id", i));
        }
        return rows;
    }

    /**
     * A cursor with the same side effect a real fetch function has: every call consumes rows and
     * they are gone for good.
     */
    private static final class Cursor implements Function<Integer, List<Map<String, Object>>> {

        private final List<Map<String, Object>> remaining;
        private final AtomicInteger calls = new AtomicInteger();

        private Cursor(List<Map<String, Object>> source) {
            this.remaining = new ArrayList<>(source);
        }

        @Override
        public List<Map<String, Object>> apply(Integer size) {
            calls.incrementAndGet();
            int take = Math.min(size, remaining.size());
            List<Map<String, Object>> chunk = List.copyOf(remaining.subList(0, take));
            remaining.subList(0, take).clear();
            return chunk;
        }

        int calls() {
            return calls.get();
        }
    }
}
