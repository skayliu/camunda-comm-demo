package com.example.camundademo.security;

import org.camunda.bpm.engine.IdentityService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 控制台管理权限：只有 {@value #ADMIN_GROUP_ID} 组成员可以查看和维护配置类数据。
 *
 * <p>为什么单独做一个门，而不是在页面上判断：前端隐藏菜单只能减少误点，
 * 接口本身必须能独立回答"这个人能不能改注册表"。因此每个管理接口第一行
 * 都调用 {@link #require(Authentication)}，前端开关只是体验优化。</p>
 *
 * <p>这里刻意只认 Camunda 内置的 {@code camunda-admin}，不把应用的 {@code admin}
 * 组也算进来——部门子流程注册表影响所有部门沟通的启动路径，授权范围要窄。</p>
 */
@Component
public class AdminAccess {

    /** Camunda 内置全权限组，控制台管理功能的唯一入口 */
    public static final String ADMIN_GROUP_ID = "camunda-admin";

    private final IdentityService identityService;

    public AdminAccess(IdentityService identityService) {
        this.identityService = identityService;
    }

    /** 未认证 401；已认证但不是 camunda-admin 成员 403 */
    public String require(Authentication authentication) {
        String userId = CurrentUser.require(authentication);
        if (!isAdmin(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "仅 " + ADMIN_GROUP_ID + " 组成员可以维护部门沟通流程登记");
        }
        return userId;
    }

    /**
     * 用户是否为 camunda-admin 成员。
     *
     * <p>每次请求查一次身份库：组成员变更（尤其被移出管理员组）应当立即生效，
     * 不能因为会话里缓存了角色而继续放行。登录态本身仍在会话中，这里是唯一一次额外查询。</p>
     */
    public boolean isAdmin(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        return identityService.createUserQuery()
                .userId(userId)
                .memberOfGroup(ADMIN_GROUP_ID)
                .count() > 0;
    }
}
