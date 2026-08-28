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
package com.entropy.database.mcp.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the two properties that made it safe to collapse the per-connection caches into one:
 * connections cannot read each other's entries, and clearing one connection leaves the others
 * intact.
 */
class ConnectionScopedCacheTest {

    private DatabaseCacheImpl shared;
    private DatabaseCache connA;
    private DatabaseCache connB;

    @BeforeEach
    void setUp() {
        shared = new DatabaseCacheImpl(1000, Duration.ofMinutes(5), Duration.ofMinutes(5));
        connA = new ConnectionScopedCache(shared, "conn-a");
        connB = new ConnectionScopedCache(shared, "conn-b");
    }

    @Test
    void sameKeyInTwoConnectionsResolvesToDifferentValues() {
        connA.putQuery("SELECT 1", "from-a");
        connB.putQuery("SELECT 1", "from-b");

        assertThat(connA.getQuery("SELECT 1")).isEqualTo("from-a");
        assertThat(connB.getQuery("SELECT 1")).isEqualTo("from-b");
    }

    @Test
    void metadataIsScopedToo() {
        connA.putMetadata("tables", "a-tables");
        assertThat(connB.getMetadata("tables")).isNull();
        assertThat(connA.getMetadata("tables")).isEqualTo("a-tables");
    }

    @Test
    void clearingOneConnectionLeavesOthersIntact() {
        connA.putQuery("q", 1);
        connA.putMetadata("m", 2);
        connB.putQuery("q", 3);
        connB.putMetadata("m", 4);

        connA.invalidateAll();

        assertThat(connA.getQuery("q")).isNull();
        assertThat(connA.getMetadata("m")).isNull();
        assertThat(connB.getQuery("q")).isEqualTo(3);
        assertThat(connB.getMetadata("m")).isEqualTo(4);
    }

    @Test
    void sizesCountOnlyTheOwnConnection() {
        connA.putQuery("q1", 1);
        connA.putQuery("q2", 2);
        connB.putQuery("q1", 3);
        connA.putMetadata("m1", 4);

        assertThat(connA.queryCacheSize()).isEqualTo(2);
        assertThat(connA.metadataCacheSize()).isEqualTo(1);
        assertThat(connA.size()).isEqualTo(3);
        assertThat(connB.queryCacheSize()).isEqualTo(1);
        assertThat(shared.size()).isEqualTo(4);
    }

    @Test
    void membershipFilterIsScoped() {
        connA.recordQueryKey("public.SELECT 1");

        assertThat(connA.mightContainQuery("public.SELECT 1")).isTrue();
        assertThat(connB.mightContainQuery("public.SELECT 1")).isFalse();
    }

    @Test
    void loaderReceivesTheUnscopedKey() {
        String loaded = connA.getMetadata("tables", key -> "loaded:" + key);

        assertThat(loaded).isEqualTo("loaded:tables");
        assertThat(connA.getMetadata("tables")).isEqualTo("loaded:tables");
    }

    @Test
    void shutdownReleasesOnlyTheOwnEntries() {
        connA.putQuery("q", 1);
        connB.putQuery("q", 2);

        connA.shutdown();

        assertThat(connB.getQuery("q")).isEqualTo(2);
    }
}
