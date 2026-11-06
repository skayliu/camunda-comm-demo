package com.example.camundademo;

import com.example.camundademo.service.AgentRuleService;
import com.example.camundademo.service.TaskDelegateService;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「可直接办结」（SIGN）代理模式的端到端测试。
 *
 * <p>CONFIRM 模式在 {@link DelegateAgentTest} 里已覆盖，本类只钉住 SIGN 的差异点：</p>
 * <ul>
 *   <li>任务直接挂在代理人名下，引擎不产生 DELEGATION_STATE_（所以 {@code DelegationCompleteGuard}
 *       不会拦），代理人能自己点通过、流程继续往下走；</li>
 *   <li>办结时委托记录由 {@code AgentDelegationFinalizeListener} 收尾成 COMPLETED，
 *       不能永远停在 PENDING；</li>
 *   <li>委托人依然能收回，收回走改派而不是 resolveTask；</li>
 *   <li>停用规则并勾选“同时收回”时，SIGN 任务也必须被收回——否则这条勾选对 SIGN 规则等于假功能。</li>
 * </ul>
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:agentsigndb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class AgentSignModeTest {

    @Autowired
    private IdentityService identityService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskDelegateService delegateService;

    @Autowired
    private AgentRuleService agentRuleService;

    /** 每个用例都从“没有任何生效规则”开始，避免上一条规则把任务提前代理走 */
    private void stopAllRules() {
        List<Map<String, Object>> rules = agentRuleService.listMine("manager");
        for (Map<String, Object> rule : rules) {
            agentRuleService.disable(String.valueOf(rule.get("id")), "manager");
        }
    }

    private void saveSignRule() {
        long now = System.currentTimeMillis();
        agentRuleService.save(null, "manager", "bob",
                new Date(now - 3_600_000L), new Date(now + 3_600_000L), "approval", "SIGN");
    }

    private ProcessInstance start() {
        identityService.setAuthenticatedUserId("applicant");
        try {
            return runtimeService.startProcessInstanceByKey("approval", Map.of("applicant", "applicant"));
        } finally {
            identityService.clearAuthentication();
        }
    }

    private Task taskOf(String user, String defKey, String pid) {
        return taskService.createTaskQuery().taskAssignee(user)
                .processInstanceId(pid).taskDefinitionKey(defKey)
                .list().stream().findFirst().orElse(null);
    }

    /** 发起流程并走到经理审批节点（此时应已被 SIGN 规则改派给 bob） */
    private Task startToManagerTask(ProcessInstance pi) {
        Task apply = taskOf("applicant", "apply", pi.getId());
        assertNotNull(apply, "流程未停在发起节点");
        taskService.complete(apply.getId());
        Task managerTask = taskOf("bob", "manager_review", pi.getId());
        assertNotNull(managerTask, "SIGN 规则没有把经理审批任务改派给 bob");
        return managerTask;
    }

    @Test
    @Order(1)
    void signModeTaskIsHeldByAgentAndCompletedDirectly() {
        stopAllRules();
        Map<String, Object> saved = agentRuleService.save(null, "manager", "bob",
                new Date(System.currentTimeMillis() - 3_600_000L),
                new Date(System.currentTimeMillis() + 3_600_000L), "approval", "SIGN");
        @SuppressWarnings("unchecked")
        Map<String, Object> rule = (Map<String, Object>) saved.get("rule");
        assertEquals("SIGN", rule.get("delegateMode"), "规则应保存为可直接办结");

        ProcessInstance pi = start();
        Task auto = startToManagerTask(pi);

        assertEquals("manager", auto.getOwner(), "OWNER_ 仍要记原办理人，否则收回失去了依据");
        assertNull(auto.getDelegationState(), "SIGN 走改派，不应产生引擎委托状态");

        List<Map<String, Object>> records = delegateService.listByTaskId(auto.getId());
        assertEquals(1, records.size());
        assertEquals("PENDING", records.get(0).get("status"));
        assertEquals("SIGN", records.get(0).get("delegateMode"));
        assertEquals("AGENT", records.get(0).get("source"));
        assertEquals("manager", records.get(0).get("owner"));
        assertEquals("bob", records.get(0).get("delegateUser"));

        // 关键差异：代理人自己点同意就能办结，不需要委托人回来确认
        taskService.complete(auto.getId());
        assertNotNull(taskOf("finance", "finance_review", pi.getId()),
                "代理人办结后流程应直接进入财务审批");

        Map<String, Object> done = delegateService.listByTaskId(auto.getId()).get(0);
        assertEquals("COMPLETED", done.get("status"), "办结后记录应收尾为 COMPLETED，不能停在 PENDING");
        assertNotNull(done.get("resolveTime"));
        assertFalse(Boolean.TRUE.equals(done.get("taskActive")), "任务已流转，不应再算作进行中");
        assertEquals(Integer.valueOf(0), Integer.valueOf(delegateService.countPendingOut("manager")),
                "已办结的代理不应继续计入“委托中”角标");
    }

    @Test
    @Order(2)
    void signModeDelegationCanBeCanceledBackToOwner() {
        stopAllRules();
        saveSignRule();

        ProcessInstance pi = start();
        Task auto = startToManagerTask(pi);

        delegateService.cancel(auto.getId(), "manager", "我自己处理");

        Task back = taskService.createTaskQuery().taskId(auto.getId()).singleResult();
        assertEquals("manager", back.getAssignee(), "收回后任务必须回到委托人名下");
        assertEquals("manager", back.getOwner());
        assertNull(back.getDelegationState(), "SIGN 收回是改派，不应残留委托状态");

        Map<String, Object> record = delegateService.listByTaskId(auto.getId()).get(0);
        assertEquals("CANCELED", record.get("status"));
        assertEquals("我自己处理", record.get("resolveContent"));

        // 收回之后本人才办得动：规则仍启用，但已有 PENDING 记录，监听器不会再次代理
        taskService.complete(auto.getId());
        assertNotNull(taskOf("finance", "finance_review", pi.getId()), "收回后本人办结应正常流转");
    }

    @Test
    @Order(3)
    void disablingRuleWithCancelActiveRecallsSignTasks() {
        stopAllRules();
        saveSignRule();

        ProcessInstance pi = start();
        Task auto = startToManagerTask(pi);

        List<Map<String, Object>> rules = agentRuleService.listMine("manager");
        Map<String, Object> rule = rules.stream()
                .filter(r -> "SIGN".equals(r.get("delegateMode")) && Integer.valueOf(1).equals(r.get("enabled")))
                .findFirst().orElseThrow();
        assertEquals(Integer.valueOf(1), rule.get("pendingCount"), "SIGN 中的任务也应计入该规则的委托中条数");

        Map<String, Object> resp = agentRuleService.disable(String.valueOf(rule.get("id")), "manager", true);
        assertEquals(Integer.valueOf(1), resp.get("canceled"), "勾选同时收回时 SIGN 任务必须被收回");
        assertEquals(Integer.valueOf(0), resp.get("skipped"));

        Task back = taskService.createTaskQuery().taskId(auto.getId()).singleResult();
        assertEquals("manager", back.getAssignee(), "停用并收回后任务应落在委托人待办里");
        assertNull(back.getDelegationState());

        Map<String, Object> record = delegateService.listByTaskId(auto.getId()).get(0);
        assertEquals("CANCELED", record.get("status"));
        assertEquals(AgentRuleService.DISABLE_CANCEL_REASON, record.get("resolveContent"));
        assertTrue(Boolean.TRUE.equals(record.get("taskActive")) || record.get("taskActive") == null,
                "任务仍在运行，记录应能看出它是被停用收回的");
    }
}
