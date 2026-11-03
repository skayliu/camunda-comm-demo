package com.example.camundademo.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * 安全配置。
 *
 * <ul>
 *   <li>{@code /api/**} 必须认证，用 HTTP Basic（demo 便于 curl / 测试）；</li>
 *   <li>其余路径（Camunda Webapp、H2 控制台、静态资源）保持放行——Camunda Webapp
 *       自带登录与鉴权过滤器，若被 Spring Security 拦下会出现"登录两次"或 401；</li>
 *   <li>CSRF 关闭：本项目只提供无状态 REST 接口，且 Camunda Webapp 有自己的 CSRF 过滤器，
 *       两者叠加会互相拦截对方请求。</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   CamundaIdentityAuthenticationProvider authenticationProvider)
            throws Exception {
        http
                .authenticationProvider(authenticationProvider)
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .httpBasic(Customizer.withDefaults())
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // 未认证返回 401 + WWW-Authenticate，而不是重定向到登录页
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }
}
