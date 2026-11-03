package com.example.camundademo.security;

import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.identity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 演示账号初始化：把测试/演示用到的用户建到 Camunda Identity 中（密码统一 demo）。
 *
 * <p>这些账号只是 demo 数据，生产环境应关闭本类，改由企业目录（LDAP / SSO / OAuth2）
 * 提供身份。用户已存在时跳过，因此可重复启动。</p>
 */
@Component
public class DemoUserInitializer implements ApplicationRunner {

    public static final String DEMO_PASSWORD = "demo";

    private static final Logger log = LoggerFactory.getLogger(DemoUserInitializer.class);

    /** 与端到端测试中的使用人保持一致；applicant2..5 用于并行隔离不同用例 */
    private static final List<String> DEMO_USERS = List.of(
            "applicant", "applicant2", "applicant3", "applicant4", "applicant5", "applicant6",
            "manager", "finance",
            "alice", "bob", "carol", "dave", "erin", "frank", "grace", "henry", "mallory");

    private final IdentityService identityService;

    public DemoUserInitializer(IdentityService identityService) {
        this.identityService = identityService;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String userId : DEMO_USERS) {
            try {
                if (identityService.createUserQuery().userId(userId).count() > 0) {
                    continue;
                }
                User user = identityService.newUser(userId);
                user.setPassword(DEMO_PASSWORD);
                user.setFirstName(userId);
                user.setLastName("(demo)");
                identityService.saveUser(user);
            } catch (RuntimeException e) {
                // 不中断启动，但必须留痕：账号缺失会让后续请求全部 401，很难排查
                log.warn("演示用户 {} 初始化失败，认证将不可用", userId, e);
            }
        }
    }
}
