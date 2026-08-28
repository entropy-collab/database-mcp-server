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

import com.entropy.database.mcp.dialect.MySqlDialect;
import com.entropy.database.mcp.dialect.OracleDialect;
import com.entropy.database.mcp.dialect.PostgresDialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The enum has to recognize the literals the dialects actually emit. When it did not, every row was
 * mapped to null, skipped with {@code continue}, and {@code readChanges} reported zero events no
 * matter how many changes the database held.
 */
class CdcChangeTypeTest {

    @ParameterizedTest
    @ValueSource(strings = {"I", "U", "D", "DDL", "T"})
    void recognizesTheDmlCodes(String code) {
        assertThat(CdcChangeType.fromCode(code)).isNotNull();
    }

    @Test
    void recognizesEveryLiteralTheDialectsProject() {
        // Oracle's Flashback Version Query yields VERSIONS_OPERATION values 'I' / 'U' / 'D'.
        String oracleSql = new OracleDialect().cdcReadChangesSql("HR", "EMPLOYEES", 1L);
        assertThat(oracleSql).contains("VERSIONS_OPERATION AS change_type");
        assertThat(CdcChangeType.fromCode("I")).isEqualTo(CdcChangeType.INSERT);
        assertThat(CdcChangeType.fromCode("U")).isEqualTo(CdcChangeType.UPDATE);
        assertThat(CdcChangeType.fromCode("D")).isEqualTo(CdcChangeType.DELETE);

        // MySQL / PostgreSQL read a trigger audit table that carries no operation column, so they
        // emit the TRIGGER_AUDIT literal - which must resolve.
        assertThat(new MySqlDialect().cdcReadChangesSql("app", "orders", 1L)).contains("'TRIGGER_AUDIT'");
        assertThat(new PostgresDialect().cdcReadChangesSql("app", "orders", 1L)).contains("'TRIGGER_AUDIT'");
        assertThat(CdcChangeType.fromCode("TRIGGER_AUDIT")).isEqualTo(CdcChangeType.TRIGGER_AUDIT);
        assertThat(CdcChangeType.fromCode("FLASHBACK")).isEqualTo(CdcChangeType.FLASHBACK);
    }

    @Test
    void acceptsEnumNamesBecauseMcpClientsSubscribeWithThem() {
        assertThat(CdcChangeType.fromCode("INSERT")).isEqualTo(CdcChangeType.INSERT);
        assertThat(CdcChangeType.fromCode("delete")).isEqualTo(CdcChangeType.DELETE);
    }

    @Test
    void unknownCodeResolvesToUnknownRatherThanBeingDropped() {
        assertThat(CdcChangeType.fromCode("SOMETHING_NEW")).isNull();
        assertThat(CdcChangeType.fromCodeOrUnknown("SOMETHING_NEW")).isEqualTo(CdcChangeType.UNKNOWN);
        assertThat(CdcChangeType.fromCodeOrUnknown(null)).isEqualTo(CdcChangeType.UNKNOWN);
    }
}
