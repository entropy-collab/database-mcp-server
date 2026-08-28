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

import com.entropy.database.mcp.security.SqlValidator;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * AOP aspect for automatic SQL validation before execution.
 *
 * <p>The pointcut can only ever take effect on {@link
 * com.entropy.database.mcp.facade.RoutingDatabaseFacade}, which is a {@code @Service} and therefore
 * proxied. Its delegate {@code ByokDatabaseFacade} is package-private and constructed with
 * {@code new} per connection, so it is not a Spring bean and Spring AOP never weaves it — a pointcut
 * aimed at the delegate matches nothing at runtime, which is how SQL validation came to be declared
 * but inert.
 *
 * <p>The same proxy constraint means calls the routing facade makes to its own methods bypass the
 * advice: {@code copyRows} reads its source through an internal {@code queryRows} call and is not
 * validated here.
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@Component
public class SqlValidationAspect {

    private static final Logger log = LoggerFactory.getLogger(SqlValidationAspect.class);
    private final SqlValidator sqlValidator;

    public SqlValidationAspect(SqlValidator sqlValidator) {
        this.sqlValidator = sqlValidator;
    }

    /**
     * Every facade entry point that takes a caller-supplied SQL string as its first argument.
     *
     * <p>{@code queryRows} and {@code executeUpdate} are named explicitly rather than left to the
     * {@code execute*}/{@code explain*} prefixes because they are the generic escape hatches the
     * tools use for arbitrary dialect SQL; without them the majority of statements reaching the
     * database would never be validated.
     */
    @Pointcut("(execution(* com.entropy.database.mcp.facade..*.execute*(..))"
            + " || execution(* com.entropy.database.mcp.facade..*.explain*(..))"
            + " || execution(* com.entropy.database.mcp.facade..*.queryRows(..))"
            + " || execution(* com.entropy.database.mcp.facade..*.copyRows(..)))"
            + " && args(java.lang.String, ..)")
    public void sqlExecutionMethod() {}

    @Around("sqlExecutionMethod()")
    public Object validateSqlBeforeExecution(ProceedingJoinPoint pjp) throws Throwable {
        String methodName = pjp.getSignature().getName();
        String sql = (String) pjp.getArgs()[0];
        log.debug("SqlValidationAspect: intercepting {} with sql length={}",
                methodName, sql != null ? sql.length() : 0);
        if (isWriteMethod(methodName)) {
            sqlValidator.validateDdl(sql);
        } else {
            sqlValidator.validateSelect(sql);
        }
        return pjp.proceed();
    }

    /**
     * {@code executeUpdate} carries INSERT/UPDATE/DELETE and PL/SQL blocks, so it must take the same
     * path as {@code executeDdl}: the read-only validator rejects every non-SELECT operation and
     * would make the write entry point unusable rather than safer.
     */
    private boolean isWriteMethod(String methodName) {
        return methodName.startsWith("executeDdl")
                || methodName.startsWith("executeUpdate");
    }
}
