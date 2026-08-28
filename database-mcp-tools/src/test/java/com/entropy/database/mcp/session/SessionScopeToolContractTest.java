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

import com.entropy.database.mcp.extension.CustomToolRegistrar;
import com.entropy.database.mcp.tools.McpSessionDispatchTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Pins the scope contract of the session key/value tools.
 *
 * <p>The four tools used to write to whatever {@link MultiSessionContext}'s no-scope overloads
 * picked, which under {@code protocol: STATELESS} is a {@code call:<uuid>} partition that
 * {@link McpToolContext#close()} discards — so {@code sessionGet} could never see what
 * {@code sessionStore} wrote, and the tools said so in their own descriptions while staying exposed
 * to the model. They now take an optional {@code scope}, and these tests assert both halves of that:
 * cross-call visibility when a scope is supplied, and an explicit {@code callScoped} flag when it is
 * not.
 *
 * <p>Lives in the {@code session} package because the contract under test is the scope semantics of
 * {@link MultiSessionContext} / {@link McpToolContext}, not the tool plumbing.
 */
class SessionScopeToolContractTest {

    private static final String NS = MultiSessionContext.NAMESPACE_SCRATCH;

    private MultiSessionContext store;
    private McpSessionDispatchTool tool;

    @BeforeEach
    void setUp() {
        store = new MultiSessionContext();
        tool = new McpSessionDispatchTool(mock(CustomToolRegistrar.class), store);
    }

    @AfterEach
    void clearThreadLocal() {
        McpToolContext.current().ifPresent(McpToolContext::close);
    }

    @Test
    @DisplayName("without a scope the value dies with the call, and the response says so")
    void withoutAScopeTheStoreIsCallScoped() {
        Map<String, Object> stored = inCall(() -> tool.sessionStore(NS, "k", "v", null));
        assertThat(stored).containsEntry("callScoped", true);

        Map<String, Object> fetched = inCall(() -> tool.sessionGet(NS, "k", null));
        assertThat(fetched).containsEntry("found", false).containsEntry("callScoped", true);
        assertThat(fetched.get("value")).isNull();
    }

    @Test
    @DisplayName("a caller-supplied scope makes the value readable from the next call")
    void aSuppliedScopeSurvivesAcrossCalls() {
        Map<String, Object> stored = inCall(() -> tool.sessionStore(NS, "k", "v", "tenant-a"));
        assertThat(stored).containsEntry("callScoped", false).containsEntry("scope", "tenant-a");

        Map<String, Object> fetched = inCall(() -> tool.sessionGet(NS, "k", "tenant-a"));
        assertThat(fetched)
                .containsEntry("found", true)
                .containsEntry("value", "v")
                .containsEntry("callScoped", false);
    }

    @Test
    @DisplayName("one scope cannot read another scope's entries")
    void scopesStayIsolated() {
        inCall(() -> tool.sessionStore(NS, "k", "a-value", "tenant-a"));
        inCall(() -> tool.sessionStore(NS, "k", "b-value", "tenant-b"));

        assertThat(inCall(() -> tool.sessionGet(NS, "k", "tenant-a"))).containsEntry("value", "a-value");
        assertThat(inCall(() -> tool.sessionGet(NS, "k", "tenant-b"))).containsEntry("value", "b-value");
    }

    @Test
    @DisplayName("sessionKeys and sessionRemove address the scope they are given")
    void keysAndRemoveHonourTheScope() {
        inCall(() -> tool.sessionStore(NS, "k1", "v1", "tenant-a"));
        inCall(() -> tool.sessionStore(NS, "k2", "v2", "tenant-a"));

        Map<String, Object> keys = inCall(() -> tool.sessionKeys(NS, "tenant-a"));
        assertThat(keys).containsEntry("count", 2);
        assertThat((Set<String>) keys.get("keys")).containsExactlyInAnyOrder("k1", "k2");

        // No scope: only this call's own partition, which is empty.
        assertThat(inCall(() -> tool.sessionKeys(NS, null))).containsEntry("count", 0);

        inCall(() -> tool.sessionRemove(NS, "k1", "tenant-a"));
        assertThat(inCall(() -> tool.sessionGet(NS, "k1", "tenant-a"))).containsEntry("found", false);
        assertThat(inCall(() -> tool.sessionGet(NS, "k2", "tenant-a"))).containsEntry("found", true);
    }

    @Test
    @DisplayName("sessionPurge with a scope drops that scope, leaving the others alone")
    void purgeWithAScopeDropsOnlyThatScope() {
        inCall(() -> tool.sessionStore(NS, "k", "a-value", "tenant-a"));
        inCall(() -> tool.sessionStore(NS, "k", "b-value", "tenant-b"));

        Map<String, Object> purged = inCall(() -> tool.sessionPurge("tenant-a"));
        assertThat(purged).containsEntry("scope", "tenant-a").containsEntry("scopeExisted", true);

        assertThat(inCall(() -> tool.sessionGet(NS, "k", "tenant-a"))).containsEntry("found", false);
        assertThat(inCall(() -> tool.sessionGet(NS, "k", "tenant-b"))).containsEntry("found", true);
    }

    @Test
    @DisplayName("sessionPurge without a scope keeps unexpired entries and reports no scope")
    void purgeWithoutAScopeOnlyClearsExpiredEntries() {
        inCall(() -> tool.sessionStore(NS, "k", "v", "tenant-a"));

        Map<String, Object> purged = inCall(() -> tool.sessionPurge(null));
        assertThat(purged).containsEntry("purged", true);
        assertThat(purged.get("scope")).isNull();

        assertThat(inCall(() -> tool.sessionGet(NS, "k", "tenant-a"))).containsEntry("found", true);
    }

    @Test
    @DisplayName("getSessionInfo reports whether the default scope survives the call")
    void sessionInfoExposesTheScopeSemantics() {
        Map<String, Object> info = inCall(tool::getSessionInfo);

        assertThat(info).containsEntry("callScoped", true);
        assertThat(info.get("scope")).isEqualTo(info.get("sessionId"));
        assertThat((String) info.get("scope")).startsWith("call:");
    }

    /**
     * Runs {@code action} inside its own {@link McpToolContext}, which is what one MCP tool call
     * looks like: the context — and therefore the default scope — is gone when it returns.
     */
    private static <T> T inCall(java.util.function.Supplier<T> action) {
        McpToolContext context = McpToolContext.create();
        try {
            return action.get();
        } finally {
            context.close();
        }
    }
}
