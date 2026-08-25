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

import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.properties.CdcProperties;
import com.entropy.database.mcp.tools.CdcTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code entropy.mcp.database.cdc.enabled} used to be a value that only getCdcConfig echoed back,
 * while {@code enable-mirror-tables} and {@code ddl.allowed} were real gates: turning CDC "off" left
 * every tool fully operational. These tests pin it down as a gate of the same kind, and pin down the
 * two diagnostics tools that must stay reachable so an operator can see *why* everything is refused.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CdcToolsEnabledGateTest {

    private static final String CONNECTION = "prod";
    private static final String CONFIG_KEY = "entropy.mcp.database.cdc.enabled";

    @Mock
    private CdcService cdcService;
    @Mock
    private Environment environment;

    private CdcTools disabledTools() {
        // ddl.allowed=true so a refusal cannot be credited to the DDL gate instead.
        when(environment.getProperty("entropy.mcp.database.ddl.allowed", "false")).thenReturn("true");
        CdcProperties props = new CdcProperties(false, false, 1000, 1000L, true, 10, true);
        return new CdcTools(cdcService, props, environment);
    }

    private CdcTools enabledTools() {
        when(environment.getProperty("entropy.mcp.database.ddl.allowed", "false")).thenReturn("true");
        return new CdcTools(cdcService, new CdcProperties(), environment);
    }

    @Test
    void readChangesIsRefusedAndNamesTheConfigKey() {
        CdcTools tools = disabledTools();

        assertThatThrownBy(() -> tools.readChanges(CONNECTION, "app", "orders", 100L))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining(CONFIG_KEY)
                .hasMessageContaining("readChanges");

        verify(cdcService, never()).readChanges(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void getCurrentLsnIsRefusedAndNamesTheConfigKey() {
        CdcTools tools = disabledTools();

        assertThatThrownBy(() -> tools.getCurrentLsn(CONNECTION))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining(CONFIG_KEY);

        verify(cdcService, never()).getLastLsn(anyString());
    }

    @Test
    void registerSubscriptionIsRefusedAndNamesTheConfigKey() {
        CdcTools tools = disabledTools();

        assertThatThrownBy(() -> tools.registerSubscription(CONNECTION, "sub", "app", "orders_*", "INSERT", 1000L))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining(CONFIG_KEY);

        verify(cdcService, never()).registerSubscription(any());
    }

    @Test
    void createMirrorTableIsRefusedEvenWhenDdlAndMirrorTablesAreAllowed() {
        CdcTools tools = disabledTools();

        assertThatThrownBy(() -> tools.createMirrorTable(CONNECTION, "app", "orders", "stage", "orders_copy"))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining(CONFIG_KEY);

        verify(cdcService, never()).createMirrorTable(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void getCdcStatusIsRefusedBecauseItReadsTheWatermarkFromTheDatabase() {
        CdcTools tools = disabledTools();

        assertThatThrownBy(() -> tools.getCdcStatus(CONNECTION))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining(CONFIG_KEY);

        verify(cdcService, never()).getStatus(anyString());
    }

    @Test
    void configAndSupportCheckStayAvailableForDiagnostics() {
        CdcTools tools = disabledTools();
        when(cdcService.isCdcSupported(CONNECTION)).thenReturn(false);

        Map<String, Object> config = tools.getCdcConfig();
        assertThat(config).containsEntry("enabled", false);

        Map<String, Object> support = tools.checkCdcSupport(CONNECTION);
        assertThat(support).containsEntry("cdcSupported", false);
    }

    /** In-memory registry only: it must stay usable so leftover subscriptions can be inspected and cleaned. */
    @Test
    void subscriptionRegistryToolsStayAvailable() {
        CdcTools tools = disabledTools();
        when(cdcService.listSubscriptions(CONNECTION)).thenReturn(List.of());

        assertThat(tools.listSubscriptions(CONNECTION)).containsEntry("totalCount", 0);
        assertThat(tools.unregisterSubscription("sub")).containsEntry("unsubscribed", "sub");
        verify(cdcService).unregisterSubscription("sub");
    }

    @Test
    void nothingIsRefusedWhenCdcIsEnabled() {
        CdcTools tools = enabledTools();
        when(cdcService.getLastLsn(CONNECTION)).thenReturn(1_735_689_600L);

        assertThat(tools.getCurrentLsn(CONNECTION)).containsEntry("currentLsn", 1_735_689_600L);
    }

    @Test
    void aBlankConnectionIsStillRejectedWhenCdcIsEnabled() {
        CdcTools tools = enabledTools();

        assertThatThrownBy(() -> tools.getCurrentLsn(" "))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("connection");

        verifyNoInteractions(cdcService);
    }
}
