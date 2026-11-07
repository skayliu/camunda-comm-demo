package com.example.camundademo;

import com.example.camundademo.security.DemoUserInitializer;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.identity.Group;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 部门组初始化的回归测试。
 *
 * <p>部门沟通依赖 {@code dept_handle} 上的候选组 {@code ${deptId}}，而 Camunda 7.24
 * 默认只允许 {@code [a-zA-Z0-9]+|camunda-admin} 作为资源 ID。一旦有人删掉
 * {@code application.yml} 里的 {@code groupResourceWhitelistPattern}，建组会抛
 * {@code Group has an invalid id}；更隐蔽的是 {@link DemoUserInitializer} 对该异常
 * 只记 WARN 不中断启动，应用照常运行，直到有人点开部门沟通才发现“无人可处理”。</p>
 *
 * <p>因此这里同时钉住两层：</p>
 *
 * <ol>
 *   <li>配置层：组 ID 白名单确实放开了下划线，且没有丢掉内置的 {@code camunda-admin}；</li>
 *   <li>数据层：两个部门组真的建出来了，类型为 {@code DEPARTMENT}，成员关系齐全。</li>
 * </ol>
 */
@SpringBootTest
@TestPropertySource(properties = {
        // 独立 H2 库：部门组数据不与其它测试类互相影响
        "spring.datasource.url=jdbc:h2:mem:deptgroupdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class DeptGroupInitializationTest {

    private static final Map<String, String> DEPT_NAMES = Map.of(
            "dept_it", "IT 部",
            "dept_admin", "行政部");

    private static final Map<String, List<String>> DEPT_MEMBERS = Map.of(
            "dept_it", List.of("alice", "bob"),
            "dept_admin", List.of("carol", "dave"));

    @Autowired
    private IdentityService identityService;

    @Autowired
    private DemoUserInitializer demoUserInitializer;

    @Autowired
    private ProcessEngine processEngine;

    /**
     * 显式再跑一次初始化：不依赖 ApplicationRunner 在测试上下文里的执行时机，
     * 同时验证该初始化可重复执行（组与成员已存在时跳过）。
     */
    @BeforeEach
    void ensureInitialized() {
        demoUserInitializer.run(null);
    }

    @Test
    void whitelistPatternAcceptsDepartmentGroupIds() {
        String pattern = processEngine.getProcessEngineConfiguration().getGroupResourceWhitelistPattern();

        assertNotNull(pattern,
                "未读到 groupResourceWhitelistPattern，检查 application.yml 的 camunda.bpm.generic-properties");
        assertTrue(Pattern.matches(pattern, "dept_it"),
                "组 ID 白名单必须允许下划线，否则 dept_it 无法创建，实际规则: " + pattern);
        assertTrue(Pattern.matches(pattern, "dept_admin"),
                "组 ID 白名单必须允许下划线，否则 dept_admin 无法创建，实际规则: " + pattern);
        assertTrue(Pattern.matches(pattern, "camunda-admin"),
                "自定义白名单会整体覆盖默认值，必须显式保留 camunda-admin，实际规则: " + pattern);
    }

    @Test
    void departmentGroupsExistWithDepartmentType() {
        DEPT_NAMES.forEach((groupId, expectedName) -> {
            Group group = identityService.createGroupQuery().groupId(groupId).singleResult();
            assertNotNull(group, "部门组 " + groupId + " 不存在：查看启动日志是否有 "
                    + "“Group has an invalid id”，通常是白名单配置被删或写错");
            assertEquals("DEPARTMENT", group.getType(),
                    "部门组 " + groupId + " 的类型必须是 DEPARTMENT，沟通对象选择器据此区分人与部门");
            assertEquals(expectedName, group.getName(), "部门组 " + groupId + " 的名称不符");
        });
    }

    @Test
    void departmentMembersAreAssigned() {
        DEPT_MEMBERS.forEach((groupId, userIds) -> {
            assertEquals(userIds.size(),
                    identityService.createUserQuery().memberOfGroup(groupId).count(),
                    "部门组 " + groupId + " 的成员数不符");

            userIds.forEach(userId -> assertEquals(1,
                    identityService.createGroupQuery().groupMember(userId).groupId(groupId).count(),
                    userId + " 应当且仅应当属于部门组 " + groupId + " 一次"));
        });
    }

    /**
     * 部门组被误删后，重启（等价于再跑一次初始化）必须能自动重建，成员关系一并恢复。
     *
     * <p>{@link DemoUserInitializer} 对每个账号、每个组都做“存在则跳过、不存在则创建”，
     * 这条用例就是把这个自愈能力钉住：否则一旦有人在运维时删了部门组，
     * 应用能照常启动、日志也干净，直到有人点开部门沟通才发现候选组是空的。</p>
     */
    @Test
    void departmentGroupsAreRebuiltAfterDeletion() {
        String groupId = "dept_it";
        List<String> members = DEPT_MEMBERS.get(groupId);

        try {
            // 先删成员关系再删组：模拟“部门组被删干净”的真实状态，
            // 也避免某些数据库上直接删除仍有成员的组触发外键约束
            members.forEach(userId -> identityService.deleteMembership(userId, groupId));
            identityService.deleteGroup(groupId);

            assertNull(identityService.createGroupQuery().groupId(groupId).singleResult(),
                    "前置条件不成立：" + groupId + " 没有被删掉");
            assertEquals(0, identityService.createUserQuery().memberOfGroup(groupId).count(),
                    "前置条件不成立：" + groupId + " 仍有成员");

            // 重启应用等价于再执行一次 ApplicationRunner
            demoUserInitializer.run(null);

            Group rebuilt = identityService.createGroupQuery().groupId(groupId).singleResult();
            assertNotNull(rebuilt, "部门组被删除后，初始化器应能自动重建；否则部门沟通的候选组为空");
            assertEquals("DEPARTMENT", rebuilt.getType(),
                    "重建后的部门组类型必须是 DEPARTMENT，否则沟通对象选择器认不出它是部门");
            assertEquals("IT 部", rebuilt.getName(), "重建后的部门组名称应与初始数据一致");

            assertEquals(members.size(), identityService.createUserQuery().memberOfGroup(groupId).count(),
                    "重建后部门成员数不符");
            members.forEach(userId -> assertEquals(1,
                    identityService.createGroupQuery().groupMember(userId).groupId(groupId).count(),
                    userId + " 在组重建后应重新加入 " + groupId));
        } finally {
            // 无论断言是否失败都把演示数据恢复，避免影响本类其它用例
            demoUserInitializer.run(null);
        }
    }
}
