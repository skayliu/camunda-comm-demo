package com.example.camundademo.controller;

import com.example.camundademo.security.AdminAccess;
import com.example.camundademo.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.identity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话式登录接口。
 *
 * <p>本项目原先用 HTTP Basic：凭据是每次请求头里的 Base64(user:password)，
 * 服务端没有“登录/登出”的概念——前端点退出只清了自己 localStorage 里的标记，
 * 浏览器缓存的凭据依然有效，任何人拿到一次密码就能一直调接口。这里改成
 * 服务端会话：密码只在登录请求里出现一次，之后靠 JSESSIONID 识别身份，
 * 退出时 {@code session.invalidate()} 真正让凭据失效。</p>
 *
 * <p>身份来源仍是 Camunda Identity（ACT_ID_USER），与流程里的 assignee 同源，
 * 校验方式沿用 {@link IdentityService#checkPassword(String, String)}，
 * 不另建一套账号表。</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final IdentityService identityService;
    private final SecurityContextRepository securityContextRepository;
    private final SessionRegistry sessionRegistry;
    private final AdminAccess adminAccess;

    public AuthController(IdentityService identityService,
                          SecurityContextRepository securityContextRepository,
                          SessionRegistry sessionRegistry,
                          AdminAccess adminAccess) {
        this.identityService = identityService;
        this.securityContextRepository = securityContextRepository;
        this.sessionRegistry = sessionRegistry;
        this.adminAccess = adminAccess;
    }

    /**
     * 登录：校验通过后把认证信息写入会话，返回当前用户。
     *
     * <p>失败统一返回 401 + 同一句提示，不区分“用户不存在”和“密码错误”，避免账号枚举。</p>
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) Map<String, String> body,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response) {
        String userId = body == null ? null : trim(body.get("userId"));
        String password = body == null ? null : body.get("password");

        if (userId == null || password == null || password.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", "用户名和密码不能为空"));
        }

        boolean valid;
        try {
            User user = identityService.createUserQuery().userId(userId).singleResult();
            valid = user != null && identityService.checkPassword(userId, password);
        } catch (RuntimeException e) {
            // 身份库异常不能当成“密码正确”，但也不要把异常细节抛给前端
            valid = false;
        }
        if (!valid) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "用户名或密码错误"));
        }

        // 会话固定攻击防护：已存在会话时换一个新的 session id 再写入认证信息
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }

        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        // Security 6 默认 requireExplicitSave，必须显式保存才会写进 HttpSession
        securityContextRepository.saveContext(context, request, response);

        // 单账号单会话：本接口不走认证过滤器，Spring 的并发会话策略不会自动执行，
        // 必须在这里手工登记新会话、并让该账号其它会话立即过期。
        bindSingleSession(userId, request);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("displayName", displayNameOf(userId));
        // 前端据此决定是否显示「部门沟通流程登记」菜单；接口侧仍会独立校验，
        // 这里的值只用于少发一次必然 403 的请求
        result.put("camundaAdmin", adminAccess.isAdmin(userId));
        return ResponseEntity.ok(result);
    }

    /**
     * 取 CSRF 令牌。
     *
     * <p>会话登录后，身份凭据变成浏览器自动携带的 Cookie，跨站页面也能顺带发出，
     * 因此写操作必须带 CSRF 令牌。令牌由 {@code CookieCsrfTokenRepository} 维护：
     * 本接口把令牌同时写进 {@code XSRF-TOKEN} cookie 与响应体，前端放进
     * {@code X-XSRF-TOKEN} 请求头即可。</p>
     *
     * <p>放在 {@code /api/auth/**} 下且免认证：登录请求本身也要带令牌（防登录 CSRF），
     * 而此时用户还没有会话；退出登录后前端也需要它才能再次登录。</p>
     */
    @GetMapping("/csrf")
    public Map<String, Object> csrf(CsrfToken csrfToken) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", csrfToken.getToken());
        result.put("headerName", csrfToken.getHeaderName());
        result.put("parameterName", csrfToken.getParameterName());
        return result;
    }

    /**
     * 退出：清空上下文并销毁会话，同时让浏览器立刻丢弃 JSESSIONID。
     *
     * <p>未登录时也返回成功——退出应当是幂等的，前端不必区分“本来就没登录”。</p>
     */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            // 先从登记表移除，再销毁会话；两步都做，避免监听器未触发时留下死条目
            sessionRegistry.removeSessionInformation(session.getId());
            try {
                session.invalidate();
            } catch (IllegalStateException ignored) {
                // 会话已失效，等同于退出成功
            }
        }
        ResponseCookie expired = ResponseCookie.from("JSESSIONID", "")
                .path("/")
                .httpOnly(true)
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, expired.toString());
        return Map.of("success", true);
    }

    /**
     * 当前登录用户。
     *
     * <p>前端路由守卫靠它判断登录态：刷新页面后内存态丢失，但会话还在，
     * 由这里重新确认，避免再用一个可被随手写入的 localStorage 标记当“已登录”。</p>
     */
    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        String userId = CurrentUser.require(authentication);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("displayName", displayNameOf(userId));
        result.put("camundaAdmin", adminAccess.isAdmin(userId));
        return result;
    }

    /**
     * 登记当前会话，并让同一账号的其它会话立即过期。
     *
     * <p>过期只是打标记：旧浏览器的下一个请求到达 {@code ConcurrentSessionFilter} 时
     * 才会被销毁并拿到 401。这样不会在登录请求里直接去 invalidate 别人的 HttpSession
     * （容器可能已回收，容易抛 IllegalStateException），也不会打断旧页面正在进行的请求。</p>
     */
    private void bindSingleSession(String userId, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            // 理论上 saveContext 已经创建会话；没有就说明上下文没落库，登记也无意义
            log.warn("账号 {} 登录后未拿到会话，跳过单会话登记", userId);
            return;
        }
        String currentSessionId = session.getId();
        for (SessionInformation info : sessionRegistry.getAllSessions(userId, false)) {
            if (!currentSessionId.equals(info.getSessionId())) {
                info.expireNow();
                log.info("账号 {} 在新会话 {} 登录，旧会话 {} 已标记失效",
                        userId, currentSessionId, info.getSessionId());
            }
        }
        sessionRegistry.registerNewSession(currentSessionId, userId);
    }

    private String displayNameOf(String userId) {
        try {
            User user = identityService.createUserQuery().userId(userId).singleResult();
            if (user == null) {
                return userId;
            }
            String first = user.getFirstName();
            String last = user.getLastName();
            StringBuilder sb = new StringBuilder();
            if (first != null && !first.isBlank()) {
                sb.append(first);
            }
            if (last != null && !last.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(last);
            }
            return sb.isEmpty() ? userId : sb.toString();
        } catch (RuntimeException e) {
            return userId;
        }
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
