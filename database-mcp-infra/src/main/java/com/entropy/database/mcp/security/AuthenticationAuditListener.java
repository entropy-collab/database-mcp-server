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
package com.entropy.database.mcp.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.stereotype.Component;

/**
 * 登录失败事件写审计。
 *
 * <h2>为什么只有失败，没有成功</h2>
 * <p>Basic 认证是<b>每请求认证一次</b>：{@code AuthenticationSuccessEvent} 会在每个 API 调用上发一条，
 * 审计表会被刷满，且每条长得一模一样——"admin 登录成功"出现一万次提供零信息增量。
 *
 * <p>表单登录的成功事件在 {@code SecurityConfig} 的 {@code successHandler} 里手动写，而不是用这个监听器：
 * 那样才能只覆盖"浏览器登录"这一个入口，而不是把 Basic 的成功一起收进来。退出同理，在
 * {@code logoutSuccessHandler} 里写。
 *
 * <p>失败没有这个问题：失败的 Basic 请求不会产生 {@code AuthenticationSuccessEvent}，而
 * {@code AbstractAuthenticationFailureEvent} <b>无论哪种认证机制</b>（表单 / Basic / JWT）都只在
 * "口令不对、账号锁定、凭据过期"这类真正的失败上发一条。它正好是爆破检测需要的那个信号。
 */
@Component
public class AuthenticationAuditListener {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationAuditListener.class);

    private final QueryAuditLogger auditLogger;

    public AuthenticationAuditListener(QueryAuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    /**
     * 登录失败。{@code tool=auth:login-failed}，用来和普通的 SQL 审计区分。
     *
     * <p>用户名从 {@code authentication.getName()} 取——失败事件里也有，因为 Spring 先解析凭据、
     * 再校验口令，到发事件的时候用户名已经拿到了（否则报错都没办法说"谁"）。
     *
     * <p>异常类名放在 {@code error} 列：{@code BadCredentialsException}（口令不对）与
     * {@code DisabledException}（账号停用）的下一步动作不同，只放一个"登录失败"区分不了。
     * 异常消息可能包含口令片段（某些 {@code AuthenticationProvider} 的消息里会引用输入），
     * 所以只取类名，不取 {@code getMessage()}。
     */
    @EventListener
    public void onAuthenticationFailure(AbstractAuthenticationFailureEvent event) {
        String username = event.getAuthentication().getName();
        String exceptionType = event.getException().getClass().getSimpleName();
        log.info("Authentication failed for '{}': {}", username, exceptionType);
        auditLogger.log(
                "auth:login-failed",
                "LOGIN " + username,
                0, 0L, false,
                exceptionType,
                null);
    }
}
