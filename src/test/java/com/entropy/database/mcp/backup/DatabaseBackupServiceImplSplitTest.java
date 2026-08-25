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
package com.entropy.database.mcp.backup;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Statement splitting guards the restore path: a mis-split statement either fails to execute or
 * silently truncates restored data, so the quoting rules are asserted directly.
 */
class DatabaseBackupServiceImplSplitTest {

    @Test
    void splitsOnTopLevelSemicolons() {
        var statements = DatabaseBackupServiceImpl.splitStatements(
                "INSERT INTO T (A) VALUES (1);\nINSERT INTO T (A) VALUES (2);");

        assertThat(statements).containsExactly(
                "INSERT INTO T (A) VALUES (1)",
                "INSERT INTO T (A) VALUES (2)");
    }

    @Test
    void keepsSemicolonInsideStringLiteral() {
        var statements = DatabaseBackupServiceImpl.splitStatements(
                "INSERT INTO T (A) VALUES ('a;b');INSERT INTO T (A) VALUES ('c');");

        assertThat(statements).containsExactly(
                "INSERT INTO T (A) VALUES ('a;b')",
                "INSERT INTO T (A) VALUES ('c')");
    }

    @Test
    void handlesEscapedQuoteFollowedBySemicolon() {
        var statements = DatabaseBackupServiceImpl.splitStatements(
                "INSERT INTO T (A) VALUES ('it''s; fine');INSERT INTO T (A) VALUES (2);");

        assertThat(statements).containsExactly(
                "INSERT INTO T (A) VALUES ('it''s; fine')",
                "INSERT INTO T (A) VALUES (2)");
    }

    @Test
    void keepsSemicolonInsideQuotedIdentifier() {
        var statements = DatabaseBackupServiceImpl.splitStatements(
                "INSERT INTO \"odd;name\" (A) VALUES (1);");

        assertThat(statements).containsExactly("INSERT INTO \"odd;name\" (A) VALUES (1)");
    }

    @Test
    void dropsLineComments() {
        var statements = DatabaseBackupServiceImpl.splitStatements(
                "-- a comment; not a statement\nINSERT INTO T (A) VALUES (1);");

        assertThat(statements).containsExactly("INSERT INTO T (A) VALUES (1)");
    }

    @Test
    void toleratesMissingTrailingSemicolon() {
        var statements = DatabaseBackupServiceImpl.splitStatements("INSERT INTO T (A) VALUES (1)");

        assertThat(statements).containsExactly("INSERT INTO T (A) VALUES (1)");
    }

    @Test
    void returnsEmptyForNullOrBlank() {
        assertThat(DatabaseBackupServiceImpl.splitStatements(null)).isEmpty();
        assertThat(DatabaseBackupServiceImpl.splitStatements("   \n ")).isEmpty();
        assertThat(DatabaseBackupServiceImpl.splitStatements(";;;")).isEmpty();
    }
}
