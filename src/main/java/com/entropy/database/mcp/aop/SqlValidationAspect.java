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
import org.springframework.stereotype.Component;

/**
 * AOP aspect for automatic SQL validation before execution.
 * Eliminates explicit sqlValidator.validateSelect/validateDdl calls in business methods.
 */
@Aspect
@Component
public class SqlValidationAspect {

    private final SqlValidator sqlValidator;

    public SqlValidationAspect(SqlValidator sqlValidator) {
        this.sqlValidator = sqlValidator;
    }

    @Around("(execution(* com.entropy.database.mcp.facade.DatabaseFacade.execute*(..)) || " +
            "execution(* com.entropy.database.mcp.facade.DatabaseFacade.explain*(..)) || " +
            "execution(* com.entropy.database.mcp.facade.ByokDatabaseFacade.execute*(..)) || " +
            "execution(* com.entropy.database.mcp.facade.ByokDatabaseFacade.explain*(..)) || " +
            "execution(* com.entropy.database.mcp.repository.ExecutionPlanRepositoryImpl.analyze*(..))) && args(sql, ..)")
    public Object validateSqlBeforeExecution(ProceedingJoinPoint pjp, String sql) throws Throwable {
        String methodName = pjp.getSignature().getName();
        if (methodName.toLowerCase().contains("ddl") || methodName.toLowerCase().contains("update") || methodName.toLowerCase().contains("insert")) {
            sqlValidator.validateDdl(sql);
        } else {
            sqlValidator.validateSelect(sql);
        }
        return pjp.proceed();
    }
}
