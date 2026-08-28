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
package com.entropy.database.mcp.byok;

import com.entropy.database.mcp.security.SqlValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ByokWriteRepositoryTest {

    @Test
    void executeDdlReturnsAffectedRows(@Mock JdbcTemplate jdbcTemplate, @Mock SqlValidator sqlValidator) {
        when(jdbcTemplate.update("CREATE TABLE test (id INT)")).thenReturn(1);

        ByokWriteRepository repository = new ByokWriteRepository(jdbcTemplate, sqlValidator);
        var result = repository.executeDdl("CREATE TABLE test (id INT)");

        assertThat(result).containsEntry("affectedRows", 1);
        verify(jdbcTemplate).update("CREATE TABLE test (id INT)");
    }
}
