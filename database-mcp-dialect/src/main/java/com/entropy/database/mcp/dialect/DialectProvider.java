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

import java.util.List;

/**
 * Service Provider Interface for database dialects, discovered via {@link java.util.ServiceLoader}.
 *
 * <p>The eight built-in dialects register through this same interface — see
 * {@link BuiltInDialectProviders} and
 * {@code META-INF/services/com.entropy.database.mcp.dialect.DialectProvider}. There is no
 * hard-coded dialect table in {@link DialectResolver} any more, so a third-party dialect is a
 * first-class citizen rather than a special case checked before a {@code switch}.
 *
 * <p>To add a dialect from outside this project:
 * <ol>
 *   <li>implement {@link DatabaseDialect} (usually by extending {@link AbstractDatabaseDialect});</li>
 *   <li>implement this interface, returning that dialect from {@link #getDialect()};</li>
 *   <li>declare the implementation in {@code META-INF/services/…DialectProvider}.</li>
 * </ol>
 *
 * <p><strong>Instances are shared.</strong> {@link DialectResolver} loads each provider once and
 * hands the same {@link DatabaseDialect} object to every caller, so implementations must be
 * stateless. {@code DialectStatelessnessTest} asserts this for the built-ins.
 */
public interface DialectProvider {

    /**
     * @return the canonical dialect name used in {@code entropy.mcp.database.dialect}, lowercase
     */
    String getName();

    /**
     * @return the dialect implementation; must be stateless, see the class Javadoc
     */
    DatabaseDialect getDialect();

    /**
     * Additional names that resolve to the same dialect, e.g. {@code postgresql} for
     * {@code postgres}. Matched case-insensitively, like {@link #getName()}.
     */
    default List<String> getAliases() {
        return List.of();
    }

    /**
     * JDBC URL prefixes owned by this dialect, e.g. {@code jdbc:postgresql:}. Used by
     * {@code dialect=auto} to pick a dialect from the live connection's URL.
     *
     * <p>Declaring prefixes here is what makes {@code auto} work for third-party dialects; without
     * them a custom dialect can only be selected by name.
     */
    default List<String> getJdbcUrlPrefixes() {
        return List.of();
    }

    /**
     * Whether this provider ships with the server. Only {@link BuiltInDialectProviders} returns
     * {@code true}.
     *
     * <p>This exists solely to make name collisions deterministic: when a third-party provider
     * claims a name a built-in already owns, the third-party one wins. Without the flag the winner
     * would depend on classpath order, i.e. on jar naming.
     */
    default boolean isBuiltIn() {
        return false;
    }
}
