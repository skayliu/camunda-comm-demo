package com.example.camundademo;

import com.example.camundademo.service.SignService;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 完成条件 = 任一通过（ANY）时的加签收口。
 *
 * <p>ALL 模式的路径在 {@link SignFlowTest} 里已经钉住；这里单独起一个上下文，只验证
 * “管理员把 app.add-sign.approve-mode 配成 ANY”之后，第一个加签人同意就该收敛，
 * 其余加签人的待办被引擎销毁、记录不能停在 PENDING。发起人仍然不能改这个开关。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:signanydb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "app.add-sign.approve-mode=ANY"
})
class SignApproveModeAnyTest {

    @Autowired
    private SignService signService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private IdentityService identityService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void preSignAnyModeUnlocksAfterFirstApproval() {
        ProcessInstance pi = startApproval();
        Task managerTask = startToManagerTask(pi);

        signService.add(managerTask.getId(), "manager", "PRE", List.of("bob", "carol"), false);

        List<Map<String, Object>> records = signService.listByTaskId(managerTask.getId());
        assertEquals(2, records.size());
        String subId = String.valueOf(records.get(0).get("subProcessInstanceId"));

        Task bobTask = addSignTaskOf(subId, "bob");
        Task carolTask = addSignTaskOf(subId, "carol");
        assertNotNull(bobTask, "ANY 模式不影响多人同时出待办");
        assertNotNull(carolTask);

        signService.completeAddSignTask(bobTask.getId(), "bob", true, "我先同意");

        // 任一通过即收口：原任务解锁，carol 的待办被引擎销毁
        assertEquals("NORMAL", bizStatus(managerTask.getId()), "ANY 模式下第一人同意就应解锁");
        assertNull(addSignTaskOf(subId, "carol"), "收口后其余加签人待办必须撤销");
        assertNull(runtimeService.getVariable(pi.getId(), "signRejected"),
                "轮次通过不应写驳回标记");

        List<Map<String, Object>> done = signService.listByTaskId(managerTask.getId());
        assertEquals("APPROVED", recordOf(done, "bob").get("status"));
        assertEquals("CANCELLED", recordOf(done, "carol").get("status"),
                "未表态的人必须落 CANCELLED，不能停在 PENDING");

        // 解锁后本人仍可正常办结
        signService.completeMainTask(managerTask.getId(), "manager", true, "经理同意");
        assertNotNull(taskOf(pi.getId(), "finance_review"));
    }

    @Test
    void postSignAnyModeCompletesOriginalTaskAfterFirstApproval() {
        ProcessInstance pi = startApproval();
        Task managerTask = startToManagerTask(pi);

        Map<String, Object> resp = signService.completeWithPostSign(managerTask.getId(), "manager",
                "同意，请复核", true, List.of("bob", "carol"), false);
        assertEquals(Boolean.TRUE, resp.get("postSignStarted"));

        List<Map<String, Object>> records = signService.listByTaskId(managerTask.getId());
        String subId = String.valueOf(records.get(0).get("subProcessInstanceId"));

        signService.completeAddSignTask(addSignTaskOf(subId, "bob").getId(), "bob", true, "我复核通过");

        // 原意见同意 + ANY 模式下轮次通过 → 原任务直接办结
        assertNull(taskOf(pi.getId(), "manager_review"), "ANY 模式第一人通过后原任务应被办结");
        assertNotNull(taskOf(pi.getId(), "finance_review"));
        assertEquals(Boolean.TRUE, runtimeService.getVariable(pi.getId(), "approved"));
        assertTrue(addSignTaskOf(subId, "carol") == null, "收口后其余加签人待办必须撤销");

        List<Map<String, Object>> done = signService.listByTaskId(managerTask.getId());
        assertEquals("CANCELLED", recordOf(done, "carol").get("status"));
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private ProcessInstance startApproval() {
        identityService.setAuthenticatedUserId("applicant");
        try {
            return runtimeService.startProcessInstanceByKey("approval", Map.of("applicant", "applicant"));
        } finally {
            identityService.clearAuthentication();
        }
    }

    private Task startToManagerTask(ProcessInstance pi) {
        Task apply = taskOf(pi.getId(), "apply");
        assertNotNull(apply, "流程未停在发起节点");
        taskService.complete(apply.getId());
        Task managerTask = taskOf(pi.getId(), "manager_review");
        assertNotNull(managerTask, "流程未进入经理审批节点");
        return managerTask;
    }

    private Task taskOf(String processInstanceId, String taskDefinitionKey) {
        return taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(taskDefinitionKey)
                .list().stream().findFirst().orElse(null);
    }

    private Task addSignTaskOf(String subProcessInstanceId, String assignee) {
        return taskService.createTaskQuery()
                .processInstanceId(subProcessInstanceId)
                .taskDefinitionKey("addSignTask")
                .taskAssignee(assignee)
                .list().stream().findFirst().orElse(null);
    }

    private String bizStatus(String taskId) {
        List<String> statuses = jdbcTemplate.queryForList(
                "SELECT BIZ_STATUS_ FROM BIZ_TASK_EXT_ WHERE TASK_ID_ = ?", String.class, taskId);
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    private Map<String, Object> recordOf(List<Map<String, Object>> records, String addUserId) {
        for (Map<String, Object> record : records) {
            if (addUserId.equals(record.get("addUserId"))) {
                return record;
            }
        }
        fail("未找到加签人 " + addUserId + " 的记录");
        return Map.of();
    }
}
