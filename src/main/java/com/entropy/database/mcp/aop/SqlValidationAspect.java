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
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * AOP aspect for automatic SQL validation before execution.
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@Component
public class SqlValidationAspect {

    private final SqlValidator sqlValidator;

    public SqlValidationAspect(SqlValidator sqlValidator) {
        this.sqlValidator = sqlValidator;
    }

    @Pointcut("execution(* com.entropy.database.mcp.facade..*(execute*|explain*)(..)) && args(java.lang.String, ..)")
    public void sqlExecutionMethod() {}

    @Pointcut("execution(* com.entropy.database.mcp.repository.ExecutionPlanRepositoryImpl.analyze*(..))")
    public void analyzePlanMethod() {}

    @Around("sqlExecutionMethod()")
    public Object validateSqlBeforeExecution(ProceedingJoinPoint pjp, String sql) throws Throwable {
        String methodName = pjp.getSignature().getName();
        if (isDdlMethod(methodName)) {
            sqlValidator.validateDdl(sql);
        } else {
            sqlValidator.validateSelect(sql);
        }
        return pjp.proceed();
    }

    @Around("analyzePlanMethod()")
    public Object validateAnalyzePlan(ProceedingJoinPoint pjp) throws Throwable {
        // analyzePlan methods don't have a bindable sql parameter via args(), skip validation
        return pjp.proceed();
    }

    private boolean isDdlMethod(String methodName) {
        return methodName.startsWith("executeDdl")
                || methodName.startsWith("backupSchema")
                || methodName.startsWith("backupData")
                || methodName.startsWith("diffSchema");
    }
}
