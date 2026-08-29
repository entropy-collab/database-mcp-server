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

/**
 * The prepared-statement cache sizing that {@link DatabaseDialect#dataSourceProperties} may turn
 * into driver-level properties.
 *
 * <p>This exists so the SPI does not take {@code DatabaseProperties}. That type is core's
 * {@code @ConfigurationProperties} root, and taking it as a parameter forced this module — and
 * therefore every third-party dialect — to compile against {@code spring-boot}, which in turn drags
 * spring-core / context / aop / beans / expression, micrometer, jackson, jsqlparser, caffeine and
 * guava. Measured from outside the repository, that was 23 compile-scope artifacts for a contract
 * whose implementations only ever read <em>two ints</em>: MySQL and SQL Server map them to
 * {@code prepStmtCacheSize} / {@code prepStmtCacheSqlLimit}, and no other dialect reads anything at
 * all.
 *
 * <p>Mapping configuration onto this record is the caller's job — {@code ByokDataSourceFactory}
 * does it. A dialect describes a database; it should not know how the application is configured.
 */
public record PreparedStatementCaching(int cacheSize, int sqlLimit) {
}
