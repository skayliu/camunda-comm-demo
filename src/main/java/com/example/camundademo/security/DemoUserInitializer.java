package com.example.camundademo.security;

import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.identity.Group;
import org.camunda.bpm.engine.identity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            "alice", "bob", "carol", "dave", "erin", "frank", "grace", "henry", "mallory",
            "admin");

    /**
     * 演示用管理员账号所属的组。
     *
     * <p>{@code admin} 是应用自定义的管理组，{@code camunda-admin} 是 Camunda 内置的
     * 全权限组。代理规则的“管理员强制停用他人规则”依赖其中之一，
     * 之前演示账号不在任何组里，这条路径必须手工配置才能验证。这里显式建组并授权，
     * 让 {@code admin / demo} 一登录就能走通完整链路。</p>
     */
    private static final Map<String, List<String>> DEMO_GROUP_MEMBERS = memberships();

    private static final Map<String, String> DEMO_GROUP_NAMES = groupNames();

    private static Map<String, List<String>> memberships() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("admin", List.of("admin", "camunda-admin"));
        // 部门沟通的演示数据：部门即 Camunda 的 group，部门内任意成员认领后提交意见。
        // 没有成员时部门沟通无人能处理。
        // <deptId>_clerk 是部门文书岗：通用三节点流程的文书由它解析，
        // 未配置时回退到部门成员第一个。
        map.put("alice", List.of("dept_it", "dept_it_clerk"));
        map.put("bob", List.of("dept_it"));
        map.put("carol", List.of("dept_admin", "dept_admin_clerk"));
        map.put("dave", List.of("dept_admin"));
        // 法务部：专属流程按固定岗位流转（合规初审 → 业务线专项审查 → 经理复核）
        map.put("frank", List.of("dept_legal", "dept_legal_clerk", "legal_business", "legal_manager"));
        map.put("grace", List.of("dept_legal", "legal_compliance"));
        // 财务部：预算初审 → 资金岗确认 → 经理审批
        map.put("henry", List.of("dept_finance", "dept_finance_clerk", "finance_budget", "finance_manager"));
        map.put("mallory", List.of("dept_finance", "finance_treasury"));
        return map;
    }

    private static Map<String, String> groupNames() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("admin", "Application Administrators");
        map.put("camunda-admin", "Camunda BPM Administrators");
        map.put("dept_it", "IT 部");
        map.put("dept_admin", "行政部");
        map.put("dept_legal", "法务部");
        map.put("dept_finance", "财务部");
        map.put("dept_it_clerk", "IT 部文书");
        map.put("dept_admin_clerk", "行政部文书");
        map.put("dept_legal_clerk", "法务部文书");
        map.put("dept_finance_clerk", "财务部文书");
        map.put("legal_compliance", "法务合规岗");
        map.put("legal_business", "法务业务线专员");
        map.put("legal_manager", "法务经理");
        map.put("finance_budget", "预算岗");
        map.put("finance_treasury", "资金岗");
        map.put("finance_manager", "财务经理");
        return map;
    }

    /** 组类型：部门组用 DEPARTMENT，沟通对象选择器据此区分“人”和“部门” */
    private static Map<String, String> groupTypes() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("dept_it", "DEPARTMENT");
        map.put("dept_admin", "DEPARTMENT");
        map.put("dept_legal", "DEPARTMENT");
        map.put("dept_finance", "DEPARTMENT");
        return map;
    }

    private static final Map<String, String> DEMO_GROUP_TYPES = groupTypes();

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
        ensureGroupMemberships();
    }

    /**
     * 建组并授权：组已存在则复用，成员关系已存在则跳过，可重复启动。
     *
     * <p>单独 try/catch，避免某个组创建失败（例如只读身份库）连带影响其他账号。</p>
     */
    private void ensureGroupMemberships() {
        DEMO_GROUP_MEMBERS.forEach((userId, groupIds) -> {
            for (String groupId : groupIds) {
                try {
                    String type = DEMO_GROUP_TYPES.getOrDefault(groupId, "WORKFLOW");
                    Group existing = identityService.createGroupQuery().groupId(groupId).singleResult();
                    if (existing == null) {
                        Group group = identityService.newGroup(groupId);
                        group.setName(DEMO_GROUP_NAMES.getOrDefault(groupId, groupId));
                        group.setType(type);
                        identityService.saveGroup(group);
                    } else if (!type.equals(existing.getType())) {
                        // 老库里部门组可能已存在但类型为空，补上类型后选择器才认得出
                        existing.setName(DEMO_GROUP_NAMES.getOrDefault(groupId, existing.getName()));
                        existing.setType(type);
                        identityService.saveGroup(existing);
                    }
                    if (identityService.createGroupQuery().groupMember(userId).groupId(groupId).count() == 0) {
                        identityService.createMembership(userId, groupId);
                    }
                } catch (RuntimeException e) {
                    log.warn("演示用户 {} 加入组 {} 失败，管理员能力将不可用", userId, groupId, e);
                }
            }
        });
    }
}
