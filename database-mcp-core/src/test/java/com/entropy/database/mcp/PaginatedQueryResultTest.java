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
package com.entropy.database.mcp;

import com.entropy.database.mcp.domain.PaginatedQueryResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PaginatedQueryResultTest {

    @Test
    void fromWithRows() {
        var rows = List.<Map<String, Object>>of(
            Map.of("id", 1, "name", "Alice"),
            Map.of("id", 2, "name", "Bob")
        );
        var result = PaginatedQueryResult.from(rows, "2", true);

        assertThat(result.columns()).containsExactlyInAnyOrder("id", "name");
        assertThat(result.rows()).hasSize(2);
        assertThat(result.continuationToken()).isEqualTo("2");
        assertThat(result.hasMore()).isTrue();
        assertThat(result.hasPrevious()).isTrue();
    }

    @Test
    void fromWithEmptyRows() {
        var result = PaginatedQueryResult.from(List.of(), null, false);

        assertThat(result.columns()).isEmpty();
        assertThat(result.rows()).isEmpty();
        assertThat(result.continuationToken()).isNull();
        assertThat(result.hasMore()).isFalse();
        assertThat(result.hasPrevious()).isFalse();
    }
}
