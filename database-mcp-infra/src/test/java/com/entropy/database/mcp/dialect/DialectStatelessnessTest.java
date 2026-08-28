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
package com.entropy.database.mcp.dialect;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dialects must be stateless, because {@link DialectResolver} now hands the same instance to every
 * caller instead of constructing a fresh one per {@code resolve} call.
 *
 * <p>An instance field on a dialect would therefore be shared across every BYOK connection and
 * every concurrent request. The damage would be silent — a cached table name or schema leaking from
 * one tenant's query into another's — so this is checked structurally rather than left to review.
 */
class DialectStatelessnessTest {

    @Test
    void noDialectDeclaresAnInstanceField() {
        List<String> offenders = new ArrayList<>();

        ServiceLoader.load(DialectProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .map(DialectProvider::getDialect)
                .forEach(dialect -> {
                    for (Class<?> type = dialect.getClass();
                         type != null && type != Object.class;
                         type = type.getSuperclass()) {
                        for (Field field : type.getDeclaredFields()) {
                            if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                                offenders.add(type.getSimpleName() + "." + field.getName());
                            }
                        }
                    }
                });

        assertThat(offenders)
                .as("方言实例在 DialectResolver 里是共享单例，带实例字段会跨连接、跨请求串味。"
                        + "SQL 文本请用 private static final 常量")
                .isEmpty();
    }
}
