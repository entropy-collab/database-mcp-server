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
package com.entropy.database.mcp.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the session-scope contract that replaced the clock-derived session ID.
 *
 * <p>{@code sessionId} used to be {@code System.currentTimeMillis()}, which failed in both
 * directions: two calls from one client got different values, so {@link MultiSessionContext} never
 * found what an earlier call wrote; and two calls from <em>different</em> clients landing in the
 * same millisecond got the same value, so one caller could read another's namespace. Under
 * {@code protocol: STATELESS} there is no protocol session to derive an identity from, so the
 * contract is now explicit: call-scoped by default, cross-call only when the caller supplies the
 * scope key.
 */
class McpToolContextTest {

    @AfterEach
    void clearThreadLocal() {
        McpToolContext.current().ifPresent(McpToolContext::close);
    }

    @Test
    void twoInvocationsGetDistinctCallScopes() {
        McpToolContext first = McpToolContext.create();
        String firstScope = first.sessionId();
        first.close();

        McpToolContext second = McpToolContext.create();

        assertThat(second.sessionId()).isNotEqualTo(firstScope);
        assertThat(McpToolContext.isCallScoped(second.sessionId())).isTrue();
    }

    @Test
    void sessionIdIsNotAClockValue() {
        McpToolContext context = McpToolContext.create();

        // The old implementation returned a millisecond timestamp; anything parseable as one would
        // reintroduce the collision between two callers in the same millisecond.
        assertThat(context.sessionId()).isNotEmpty();
        assertThat(isParseableAsLong(context.sessionId())).isFalse();
    }

    @Test
    void anExplicitScopeKeySurvivesAcrossInvocations() {
        McpToolContext first = McpToolContext.create("corr-1", "caller-session");
        assertThat(first.sessionId()).isEqualTo("caller-session");
        assertThat(McpToolContext.isCallScoped(first.sessionId())).isFalse();
        first.close();

        McpToolContext second = McpToolContext.create("corr-2", "caller-session");

        assertThat(second.sessionId()).isEqualTo("caller-session");
    }

    @Test
    void aBlankScopeKeyFallsBackToCallScopeRatherThanASharedBucket() {
        McpToolContext context = McpToolContext.create("corr-1", "   ");

        assertThat(McpToolContext.isCallScoped(context.sessionId())).isTrue();
    }

    @Test
    void storeIsNotVisibleToTheNextCallByDefault() {
        MultiSessionContext store = new MultiSessionContext();

        McpToolContext first = McpToolContext.create();
        store.set(MultiSessionContext.NAMESPACE_SCRATCH, "k", "v");
        assertThat(store.<String>get(MultiSessionContext.NAMESPACE_SCRATCH, "k")).isEqualTo("v");
        assertThat(store.currentScopeIsCallScoped()).isTrue();
        first.close();

        McpToolContext second = McpToolContext.create();
        try {
            assertThat(store.<String>get(MultiSessionContext.NAMESPACE_SCRATCH, "k")).isNull();
        } finally {
            second.close();
        }
    }

    @Test
    void storeIsVisibleAcrossCallsUnderAnExplicitScope() {
        MultiSessionContext store = new MultiSessionContext();

        McpToolContext first = McpToolContext.create("corr-1", "caller-session");
        store.set(MultiSessionContext.NAMESPACE_QUERIES, "k", "v");
        first.close();

        McpToolContext second = McpToolContext.create("corr-2", "caller-session");
        try {
            assertThat(store.<String>get(MultiSessionContext.NAMESPACE_QUERIES, "k")).isEqualTo("v");
            assertThat(store.currentScopeIsCallScoped()).isFalse();
        } finally {
            second.close();
        }
    }

    @Test
    void scopesDoNotSeeEachOthersEntries() {
        MultiSessionContext store = new MultiSessionContext();

        store.set("tenant-a", MultiSessionContext.NAMESPACE_QUERIES, "k", "a-value");
        store.set("tenant-b", MultiSessionContext.NAMESPACE_QUERIES, "k", "b-value");

        assertThat(store.<String>getInScope("tenant-a", MultiSessionContext.NAMESPACE_QUERIES, "k"))
                .isEqualTo("a-value");
        assertThat(store.<String>getInScope("tenant-b", MultiSessionContext.NAMESPACE_QUERIES, "k"))
                .isEqualTo("b-value");
        assertThat(store.sessionCount()).isEqualTo(2);
    }

    private static boolean isParseableAsLong(String value) {
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
