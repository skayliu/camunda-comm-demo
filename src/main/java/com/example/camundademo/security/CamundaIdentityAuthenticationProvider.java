package com.example.camundademo.security;

import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.identity.User;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * 基于 Camunda Identity 的认证提供者。
 *
 * <p>用户与密码统一由引擎的身份表（ACT_ID_USER / ACT_ID_*）维护，不再由应用侧
 * 另存一套账号，避免“流程里的 assignee”和“登录用户”两套身份对不上——沟通功能
 * 的身份校验本质上就是围绕 assignee / targetId 做的，必须同源。</p>
 *
 * <p>Camunda 的密码是加密存储的，因此不实现 UserDetailsService + PasswordEncoder，
 * 而是直接调用 {@link IdentityService#checkPassword(String, String)} 校验。</p>
 */
@Component
public class CamundaIdentityAuthenticationProvider implements AuthenticationProvider {

    private final IdentityService identityService;

    public CamundaIdentityAuthenticationProvider(IdentityService identityService) {
        this.identityService = identityService;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String userId = authentication.getName();
        Object credentials = authentication.getCredentials();
        String password = credentials == null ? null : credentials.toString();

        if (userId == null || userId.isBlank() || password == null || password.isEmpty()) {
            throw new BadCredentialsException("用户名或密码不能为空");
        }

        try {
            User user = identityService.createUserQuery().userId(userId).singleResult();
            if (user == null || !identityService.checkPassword(userId, password)) {
                // 不区分“用户不存在”和“密码错误”，避免账号枚举
                throw new BadCredentialsException("用户名或密码错误");
            }
        } catch (BadCredentialsException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BadCredentialsException("认证失败", e);
        }

        // 凭据不留在 Authentication 中，避免密码随 SecurityContext 传播
        return UsernamePasswordAuthenticationToken.authenticated(
                userId, null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
