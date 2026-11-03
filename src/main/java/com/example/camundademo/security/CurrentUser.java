package com.example.camundademo.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

/**
 * 认证上下文读取入口。
 *
 * <p>业务代码只能通过这里获取“当前用户”，禁止再从请求体、查询参数里读取
 * userId / initiator / operator——那些字段客户端可以随意伪造。</p>
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /**
     * 取当前登录用户名；未认证直接 401。
     *
     * <p>正常情况下 Security 过滤器链已拦截未认证请求，这里是纵深防御：
     * 即使某个接口被误配成 permitAll，也不会静默降级成“匿名可操作”。</p>
     */
    public static String require(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未认证，请先登录");
        }
        String name = authentication.getName();
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "认证上下文中缺少用户标识");
        }
        return name;
    }
}
