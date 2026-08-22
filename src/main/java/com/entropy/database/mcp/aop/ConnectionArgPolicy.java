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
package com.entropy.database.mcp.aop;

import java.util.Optional;

/**
 * Strategy interface for identifying connection name arguments in MCP tool invocations.
 *
 * <p>Implementations encapsulate different strategies for distinguishing a BYOK connection
 * name from other string parameters (e.g., SQL statements, table names). This follows the
 * Strategy pattern, allowing pluggable detection logic without modifying the aspect itself.
 *
 * <p>The default implementation ({@link McpToolExceptionAspect.AnnotatedConnectionArgPolicy})
 * uses reflection on Java parameter names to find the connection argument. A fallback
 * heuristic implementation is also provided for cases where parameter names are unavailable.
 */
@FunctionalInterface
public interface ConnectionArgPolicy {

    /**
     * Extract the connection name from the given method arguments.
     *
     * @param args the raw argument values passed to the tool method
     * @return the connection name if identifiable, empty otherwise
     */
    Optional<String> extractConnectionName(Object[] args);
}
