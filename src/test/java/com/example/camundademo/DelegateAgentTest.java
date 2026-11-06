package com.example.camundademo;

import com.example.camundademo.service.AgentRuleService;
import com.example.camundademo.service.BusinessException;
import com.example.camundademo.service.TaskDelegateService;
import com.example.camundademo.service.TaskTransferService;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.DelegationState;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 委托与代理端到端测试。
 *
 * <p>覆盖：委托后 OWNER/ASSIGNEE/DELEGATION_STATE 的正确关系、受托人不能转办与不能办结、
 * resolve 回到原办理人并能正常办结、代理规则在新任务上自动生效、时间段重叠被拒、
 * 规则停用后不再代理。</p>
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:delegatedb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class DelegateAgentTest {

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

    @Autowired
    private TaskTransferService transferService;

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

    @Test
    @Order(1)
    void delegateLifecycle() {
        ProcessInstance pi = start();
        Task apply = taskOf("applicant", "apply", pi.getId());
        assertNotNull(apply);
        taskService.complete(apply.getId());

        Task managerTask = taskOf("manager", "manager_review", pi.getId());
        assertNotNull(managerTask, "流程未流转到经理审批");
        String taskId = managerTask.getId();

        Map<String, Object> resp = delegateService.delegate(taskId, "manager", "bob", "出差一周");
        assertEquals("ok", resp.get("result"));

        Task delegated = taskService.createTaskQuery().taskId(taskId).singleResult();
        assertEquals("bob", delegated.getAssignee(), "委托后办理人应为受托人");
        assertEquals("manager", delegated.getOwner(), "OWNER 必须保留原办理人");
        assertEquals(DelegationState.PENDING, delegated.getDelegationState());

        assertEquals(1, delegateService.listByTaskId(taskId).size());
        assertEquals("PENDING", delegateService.listByTaskId(taskId).get(0).get("status"));

        // 受托人没有处置权：不能转办、不能直接办结
        BusinessException transferRejected = assertThrows(BusinessException.class,
                () -> transferService.transfer(taskId, "bob", "carol", null));
        assertTrue(transferRejected.getMessage().contains("委托中"));
        assertThrows(RuntimeException.class, () -> taskService.complete(taskId));

        // 受托人提交反馈 → 任务回到原办理人
        delegateService.resolve(taskId, "bob", "标准没问题，建议通过");
        Task resolved = taskService.createTaskQuery().taskId(taskId).singleResult();
        assertEquals("manager", resolved.getAssignee(), "resolve 后必须回到原办理人");
        assertEquals(DelegationState.RESOLVED, resolved.getDelegationState());

        Map<String, Object> record = delegateService.listByTaskId(taskId).get(0);
        assertEquals("RESOLVED", record.get("status"));
        assertEquals("标准没问题，建议通过", record.get("resolveContent"));

        // 原办理人确认后才真正办结
        taskService.complete(taskId);
        assertNotNull(taskOf("finance", "finance_review", pi.getId()), "流程应流转到财务审批");
    }

    @Test
    @Order(2)
    void agentRuleAppliesToNewTask() {
        long now = System.currentTimeMillis();
        agentRuleService.save(null, "manager", "bob",
                new Date(now - 3_600_000L), new Date(now + 3_600_000L), "approval");

        ProcessInstance pi = start();
        taskService.complete(taskOf("applicant", "apply", pi.getId()).getId());

        Task auto = taskOf("bob", "manager_review", pi.getId());
        assertNotNull(auto, "经理审批任务未自动代理给 bob");
        assertEquals("manager", auto.getOwner());
        assertEquals(DelegationState.PENDING, auto.getDelegationState());

        List<Map<String, Object>> records = delegateService.listByTaskId(auto.getId());
        assertEquals(1, records.size());
        assertEquals("AGENT", records.get(0).get("source"));
        assertEquals("manager", records.get(0).get("owner"));

        // 同一时间段不允许第二条生效规则
        BusinessException overlap = assertThrows(BusinessException.class,
                () -> agentRuleService.save(null, "manager", "carol",
                        new Date(now), new Date(now + 7_200_000L), null));
        assertTrue(overlap.getMessage().contains("重叠"));

        // 停用后新任务不再代理
        String ruleId = String.valueOf(agentRuleService.listMine("manager").get(0).get("id"));
        agentRuleService.disable(ruleId, "manager");

        ProcessInstance pi2 = start();
        taskService.complete(taskOf("applicant", "apply", pi2.getId()).getId());
        assertNotNull(taskOf("manager", "manager_review", pi2.getId()), "规则停用后不应再代理");
    }

    /**
     * 代理出去的待办被委托人收回后，必须真的留在委托人手里。
     *
     * <p>cancel 内部是 resolveTask：ASSIGNEE_ 改回 OWNER_ 会再次触发 assignment 事件。
     * 若代理监听器只看“有没有生效规则”，任务会在收回的同一秒被重新代理出去，
     * 表现为“点了收回、列表里还是代理人”，所以这个用例单独钉住。</p>
     */
    @Test
    @Order(3)
    void cancelAgentDelegationKeepsTaskWithOwner() {
        long now = System.currentTimeMillis();
        agentRuleService.save(null, "manager", "bob",
                new Date(now - 3_600_000L), new Date(now + 3_600_000L), "approval");

        ProcessInstance pi = start();
        taskService.complete(taskOf("applicant", "apply", pi.getId()).getId());

        Task auto = taskOf("bob", "manager_review", pi.getId());
        assertNotNull(auto, "经理审批任务未自动代理给 bob");

        delegateService.cancel(auto.getId(), "manager", "我自己处理");

        Task back = taskService.createTaskQuery().taskId(auto.getId()).singleResult();
        assertEquals("manager", back.getAssignee(), "收回后任务必须留在委托人手里，不能被代理规则再次代理");
        assertEquals(DelegationState.RESOLVED, back.getDelegationState());
        assertEquals("CANCELED", delegateService.listByTaskId(auto.getId()).get(0).get("status"));
        Map<String, Object> mine = delegateService.listByOwner("manager").stream()
                .filter(r -> auto.getId().equals(r.get("taskId")))
                .findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, mine.get("taskActive"),
                "任务仍在运行，列表里应能看到并支持再次委托");
    }
}
