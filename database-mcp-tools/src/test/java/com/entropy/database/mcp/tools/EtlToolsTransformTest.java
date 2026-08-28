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
package com.entropy.database.mcp.tools;

import com.entropy.database.mcp.dialect.DatabaseDialect;
import com.entropy.database.mcp.dialect.GenericDialect;
import com.entropy.database.mcp.exception.McpToolException;
import com.entropy.database.mcp.facade.DatabaseOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the column-mapping handling of {@link EtlTools#transformAndInsert}.
 *
 * <p>{@code validateTransformParams} only checked that a mapping had at least two segments, so
 * {@code ["(SELECT PASSWORD FROM SYS_USERS WHERE ROWNUM=1):X"]} injected a subquery straight into
 * the SELECT list — the statement stayed a valid SELECT, so {@code validateSelect} was happy.
 */
class EtlToolsTransformTest {

    private static final DatabaseDialect DIALECT = new GenericDialect();

    private DatabaseOperations routingFacade;
    private EtlTools etlTools;

    @BeforeEach
    void setUp() {
        routingFacade = mock(DatabaseOperations.class);
        when(routingFacade.getDialect(any())).thenReturn(DIALECT);
        etlTools = new EtlTools(null, routingFacade, null, null, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "(SELECT PASSWORD FROM SYS_USERS WHERE ROWNUM=1):X",
            "(SELECT PASSWORD FROM X):Y",
            "ID:X, (SELECT 1) AS Z",
            "ID:X\" FROM SYS_USERS --",
            "ID; DROP TABLE T:X"
    })
    void rejectsAMappingThatIsNotTwoIdentifiers(String mapping) {
        assertThatThrownBy(() -> etlTools.transformAndInsert(
                "conn", "SRC_TABLE", "TGT_TABLE", List.of(mapping), null, null))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("columnMapping");
    }

    @Test
    void quotesBothHalvesOfALegitimateMapping() throws Exception {
        etlTools.transformAndInsert("conn", "SRC_TABLE", "TGT_TABLE",
                List.of("id:ID", "name:FULL_NAME:upper"), null, null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(routingFacade).queryRows(sql.capture(), anyString());

        assertThat(sql.getValue())
                .contains(DIALECT.quote("id") + " AS " + DIALECT.quote("ID"))
                .contains("UPPER(" + DIALECT.quote("name") + ") AS " + DIALECT.quote("FULL_NAME"))
                .contains("FROM SRC_TABLE");
    }

    @Test
    void stillRejectsAMappingWithoutATarget() {
        assertThatThrownBy(() -> etlTools.transformAndInsert(
                "conn", "SRC_TABLE", "TGT_TABLE", List.of("id"), null, null))
                .isInstanceOf(McpToolException.class);
    }

    @Test
    void rejectsAnInjectedWhereClause() {
        assertThatThrownBy(() -> etlTools.transformAndInsert(
                "conn", "SRC_TABLE", "TGT_TABLE", List.of("id:ID"), "1=1 OR 1=1 --", null))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("whereClause");
    }
}
