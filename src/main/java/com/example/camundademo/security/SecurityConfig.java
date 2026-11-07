package com.example.camundademo.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.session.SessionInformationExpiredStrategy;

import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;

/**
 * 安全配置。
 *
 * <ul>
 *   <li>{@code /api/auth/login} 放行，其余 {@code /api/**} 必须认证；</li>
 *   <li>认证方式为<b>服务端会话</b>（JSESSIONID），不再启用 HTTP Basic——
 *       Basic 的凭据由浏览器按 origin 长期缓存，服务端没有登出手段，
 *       “退出登录”只是前端删标记，实际仍能继续调接口；</li>
 *   <li>其余路径（Camunda Webapp、H2 控制台、静态资源）保持放行——Camunda Webapp
 *       自带登录与鉴权过滤器，若被 Spring Security 拦下会出现"登录两次"或 401；</li>
 *   <li><b>CSRF 校验</b>：会话登录后凭据是浏览器自动携带的 Cookie，跨站页面可以直接借用，
 *       因此必须校验 CSRF token。采用「双提交 Cookie」方案
 *       （{@link CookieCsrfTokenRepository}）：令牌写进 {@code XSRF-TOKEN} cookie（非 HttpOnly），
 *       前端读出后放进 {@code X-XSRF-TOKEN} 请求头，服务端比对两者。
 *       校验范围只限 {@code /api/**}——Camunda Webapp 自带 {@code CsrfPreventionFilter}，
 *       若两条过滤器都作用在同一路径上会互相拦截。</li>
 *   <li><b>单账号单会话</b>：{@code maximumSessions(1)} + 自定义 {@link SessionRegistry}，
 *       同一账号在新设备登录后，旧会话被标记过期，旧浏览器下一次请求即返回 401。
 *       注意本项目的登录是 {@code AuthController} 手工写入 SecurityContext，
 *       不经过认证过滤器，因此<b>会话登记与旧会话过期必须由登录接口自己完成</b>
 *       （Spring 的 {@code ConcurrentSessionControlAuthenticationStrategy} 不会自动执行）。</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * 显式声明为 HttpSession 实现，登录时 {@code saveContext} 与后续请求的读取
     * 必须落在同一个仓库上，否则会出现“登录成功但下一个请求 401”。
     */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * 会话登记表：记录 sessionId -> 登录账号，供单会话控制查询与踢人使用。
     */
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /**
     * 把容器层面的事件（session 销毁 / 超时 / id 变更）转发给 {@link SessionRegistry}。
     *
     * <p>没有它，会话过期后登记表里会留下死条目，{@code maximumSessions(1)} 会把
     * 这些早已失效的会话算作“另一个在线会话”。Spring Boot 会自动把这个 bean
     * 注册为 Servlet 监听器。</p>
     */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    /**
     * CSRF 令牌仓库：双提交 Cookie 方案。
     *
     * <ul>
     *   <li>{@code withHttpOnlyFalse()} 让前端 JS 能读到 {@code XSRF-TOKEN}，
     *       否则页面无法把令牌放进请求头；令牌本身不是凭据，读到也无害，
     *       真正的凭据仍是 HttpOnly 的 {@code JSESSIONID}；</li>
     *   <li>令牌不落 Session，每个浏览器一份；配合 {@code SameSite=Lax}，
     *       跨站 POST 请求连 Cookie 都不会带上，形成第二道防线；</li>
     *   <li>退出登录不清除这个 Cookie：下一次登录前的 {@code /api/auth/csrf}
     *       会复用同一个令牌，避免“退出后登录页拿不到令牌”的死循环。</li>
     * </ul>
     */
    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName("XSRF-TOKEN");
        repository.setHeaderName("X-XSRF-TOKEN");
        repository.setCookiePath("/");
        repository.setCookieCustomizer(cookie -> cookie.sameSite("Lax"));
        return repository;
    }

    /**
     * 旧会话被踢后的响应：与未认证保持一致，返回 401 + JSON，
     * 让前端拦截器统一跳登录页，而不是收到 Spring 默认的英文纯文本。
     */
    @Bean
    public SessionInformationExpiredStrategy sessionInformationExpiredStrategy() {
        return event -> {
            HttpServletResponse response = event.getResponse();
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"该账号已在其它设备登录，当前会话已失效\"}");
            response.flushBuffer();
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   CamundaIdentityAuthenticationProvider authenticationProvider,
                                                   SecurityContextRepository securityContextRepository,
                                                   SessionRegistry sessionRegistry,
                                                   SessionInformationExpiredStrategy expiredStrategy,
                                                   CookieCsrfTokenRepository csrfTokenRepository)
            throws Exception {
        http
                .authenticationProvider(authenticationProvider)
                .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
                .csrf(csrf -> csrf
                        // 双提交 Cookie 用明文比对：令牌从 cookie 直接读、放进请求头，
                        // 不经过 HTML 响应体，BREACH 的前提（响应体里回显令牌）不成立
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        // 只对 /api/** 生效。Camunda Webapp（/camunda/**）、H2 控制台、
                        // 前端静态资源都由各自组件处理，混在一起会互相拦请求。
                        .ignoringRequestMatchers(request -> {
                            String uri = request.getRequestURI();
                            return !uri.startsWith("/api/");
                        }))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        // 取令牌必须免认证：会话失效后前端仍需要它才能重新登录
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s
                        // 只在需要时建会话：静态资源请求不应产生 session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        // 同一账号只保留一个有效会话；新登录顶掉旧会话而不是拒绝新登录，
                        // 否则用户换设备后会被自己上一次的会话锁在门外
                        .maximumSessions(1)
                        .maxSessionsPreventsLogin(false)
                        .sessionRegistry(sessionRegistry)
                        .expiredSessionStrategy(expiredStrategy))
                // 未认证返回 401 + JSON，而不是重定向到登录页（前端是 SPA，自己处理跳转）
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) -> {
                            response.setStatus(HttpStatus.UNAUTHORIZED.value());
                            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"message\":\"未认证，请先登录\"}");
                            response.flushBuffer();
                        })
                        // CSRF 失败与权限不足同为 403，但前端处理方式完全不同：
                        // 前者可以重取令牌后自动重试一次，后者重试多少次都没用。
                        .accessDeniedHandler((request, response, ex) -> {
                            boolean csrf = ex instanceof InvalidCsrfTokenException;
                            response.setStatus(HttpStatus.FORBIDDEN.value());
                            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write(csrf
                                    ? "{\"code\":\"CSRF_INVALID\",\"message\":\"CSRF 令牌缺失或已失效，请刷新页面后重试\"}"
                                    : "{\"code\":\"ACCESS_DENIED\",\"message\":\"没有权限执行该操作\"}");
                            response.flushBuffer();
                        }));
        return http.build();
    }
}
