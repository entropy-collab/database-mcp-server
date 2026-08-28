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

import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.stream.Stream;

/**
 * 固定内容的 {@link ObjectProvider}，用于在纯单元测试里替代容器查找。
 *
 * <p>{@link ToolCatalog} 与 {@link ToolExposureFilter} 都通过 ObjectProvider 延迟解析依赖
 * （避免 BeanPostProcessor 过早初始化普通 bean），单测里需要一个不依赖 Spring 上下文的实现。
 */
final class FixedObjectProvider<T> implements ObjectProvider<T> {

    private final List<T> values;

    private FixedObjectProvider(List<T> values) {
        this.values = values;
    }

    @SafeVarargs
    static <T> ObjectProvider<T> of(T... values) {
        return new FixedObjectProvider<>(List.of(values));
    }

    @Override
    public T getObject() {
        if (values.size() != 1) {
            throw new IllegalStateException("expected exactly one value but had " + values.size());
        }
        return values.getFirst();
    }

    @Override
    public T getObject(Object... args) {
        return getObject();
    }

    @Override
    public T getIfAvailable() {
        return values.isEmpty() ? null : values.getFirst();
    }

    @Override
    public T getIfUnique() {
        return values.size() == 1 ? values.getFirst() : null;
    }

    @Override
    public Stream<T> stream() {
        return values.stream();
    }

    @Override
    public Stream<T> orderedStream() {
        return values.stream();
    }
}
