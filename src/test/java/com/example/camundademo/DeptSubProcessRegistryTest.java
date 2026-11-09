package com.example.camundademo;

import com.example.camundademo.entity.BizCommTask;
import com.example.camundademo.security.DemoUserInitializer;
import com.example.camundademo.support.TestSession;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 部门沟通子流程注册表与两类子流程的端到端用例。
 *
 * <p>覆盖文档中「通用三节点 + 部门专属」两条路径，以及注册表的三条边界：</p>
 * <ol>
 *   <li>可选流程按部门过滤：普通部门只看到通用流程，法务部多看到自己的合同审查流程；</li>
 *   <li>通用三节点：文书分发 → 经办人意见 → 文书汇总 → 子流程结束、主待办解锁；</li>
 *   <li>专属流程：启动后直接落在固定岗位节点，不需要任何人选经办人；</li>
 *   <li>跨部门选流程必须被拒绝，且主待办不能被锁住。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:deptsubreg;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class DeptSubProcessRegistryTest {

    private static final String DEPT_IT = "dept_it";
    private static final String DEPT_LEGAL = "dept_legal";
    private static final String GENERAL_KEY = "dept_comm_general";
    private static final String LEGAL_KEY = "dept_comm_legal_contract";
    private static final String FINANCE_KEY = "dept_comm_finance_budget";

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    private int port;

    @Autowired
    private TaskService taskService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private DemoUserInitializer demoUserInitializer;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void ensureDemoIdentity() {
        demoUserInitializer.run(null);
    }

    // ==================== 1. 可选流程按部门过滤 ====================

    @Test
    void availableSubProcessesFilteredByDept() {
        List<Map<String, Object>> itList = subProcesses(DEPT_IT);
        assertEquals(1, itList.size(), "IT 部只应看到通用流程: " + itList);
        assertEquals(GENERAL_KEY, itList.get(0).get("processKey"), "唯一选项应为通用三节点流程");
        assertEquals(Boolean.TRUE, itList.get(0).get("general"), "通用流程应标记 general=true");

        List<Map<String, Object>> legalList = subProcesses(DEPT_LEGAL);
        assertEquals(2, legalList.size(), "法务部应看到通用 + 专属两条: " + legalList);
        assertTrue(legalList.stream().anyMatch(m -> LEGAL_KEY.equals(m.get("processKey"))),
                "法务部看不到自己的合同审查流程: " + legalList);
        assertFalse(legalList.stream().anyMatch(m -> FINANCE_KEY.equals(m.get("processKey"))),
                "法务部不应看到财务部的流程: " + legalList);
    }

    // ==================== 2. 通用三节点完整闭环 ====================

    @Test
    void generalProcessDispatchOpinionSummaryUnlocksSourceTask() {
        String pid = startProcessAndApply("applicant");
        String managerTaskId = findTask("manager", "manager_review", pid);

        startDeptComm(managerTaskId, DEPT_IT, "IT 部", "请确认服务器扩容方案", GENERAL_KEY);

        String recordId = deptRecordId(managerTaskId);
        ProcessInstance sub = runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(recordId).singleResult();
        assertNotNull(sub, "未启动部门沟通子流程");
        assertEquals(GENERAL_KEY, definitionKey(sub.getProcessDefinitionId()),
                "应按前端选定的通用流程启动，而不是回退到旧单节点流程");

        // 节点一：文书收文分发，办理人是部门文书
        Task dispatchTask = singleTask(sub.getId());
        assertEquals("Task_ClerkDispatch", dispatchTask.getTaskDefinitionKey(), "首节点应为文书分发节点");
        assertEquals("alice", dispatchTask.getAssignee(), "文书应解析为 dept_it_clerk 成员");

        Map<String, Object> detail = getMap("/api/comm/dept-sub/" + dispatchTask.getId(), "alice");
        assertEquals("DISPATCH", detail.get("nodeType"), "前端应据 nodeType 渲染分发表单: " + detail);
        assertNotNull(detail.get("deptMembers"), "分发节点应返回本部门候选经办人");

        // 文书把沟通分给本部门 bob，要求全部反馈
        Map<String, Object> dispatchReq = new LinkedHashMap<>();
        dispatchReq.put("handlerUserIds", List.of("bob"));
        dispatchReq.put("opinionRule", "ALL");
        dispatchReq.put("remark", "请核对扩容清单");
        Map<String, Object> dispatched = post("/api/comm/dept-sub/" + dispatchTask.getId() + "/dispatch",
                dispatchReq, "alice");
        assertFalse(Boolean.TRUE.equals(dispatched.get("error")), "文书分发失败: " + dispatched);

        // 节点二：多实例经办人意见
        Task handlerTask = taskService.createTaskQuery().processInstanceId(sub.getId())
                .taskDefinitionKey("Task_HandlerOpinion").singleResult();
        assertNotNull(handlerTask, "分发后未生成经办人待办");
        assertEquals("bob", handlerTask.getAssignee(), "多实例应分派给文书选定的经办人");

        Map<String, Object> opinionReq = new LinkedHashMap<>();
        opinionReq.put("opinion", "扩容清单无误，建议下周一执行");
        opinionReq.put("attachments", "");
        opinionReq.put("returnToClerk", false);
        Map<String, Object> opinionResp = post("/api/comm/dept-sub/" + handlerTask.getId() + "/opinion",
                opinionReq, "bob");
        assertFalse(Boolean.TRUE.equals(opinionResp.get("error")), "经办人提交意见失败: " + opinionResp);

        // 节点三：文书汇总
        Task summaryTask = taskService.createTaskQuery().processInstanceId(sub.getId())
                .taskDefinitionKey("Task_ClerkSummary").singleResult();
        assertNotNull(summaryTask, "经办人提交后应回到文书汇总节点");
        assertEquals("alice", summaryTask.getAssignee(), "汇总节点仍应是同一个文书");

        Map<String, Object> summaryReq = new LinkedHashMap<>();
        summaryReq.put("finalOpinion", "IT 部意见：扩容方案可行，下周一执行");
        summaryReq.put("actionType", "SUBMIT");
        Map<String, Object> summaryResp = post("/api/comm/dept-sub/" + summaryTask.getId() + "/summary",
                summaryReq, "alice");
        assertFalse(Boolean.TRUE.equals(summaryResp.get("error")), "文书汇总提交失败: " + summaryResp);

        // 子流程结束，主待办解锁，沟通记录回写为已反馈
        assertEquals(0, runtimeService.createProcessInstanceQuery()
                .processInstanceId(sub.getId()).count(), "汇总提交后子流程应结束");
        Map<String, Object> after = getMap("/api/comm/status/" + managerTaskId, "manager");
        assertEquals(Boolean.TRUE, after.get("canApprove"), "部门反馈后主待办应恢复可审批: " + after);
        assertEquals(BizCommTask.STATUS_REPLIED, deptRecord(managerTaskId).get("status"),
                "沟通记录应回写为已反馈");
    }

    // ==================== 3. 专属流程按固定岗位启动 ====================

    @Test
    void legalProcessStartsAtFixedCompliancePost() {
        String pid = startProcessAndApply("applicant3");
        String managerTaskId = findTask("manager", "manager_review", pid);

        startDeptComm(managerTaskId, DEPT_LEGAL, "法务部", "请审查合同条款合规性", LEGAL_KEY);

        String recordId = deptRecordId(managerTaskId);
        ProcessInstance sub = runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(recordId).singleResult();
        assertNotNull(sub, "未启动法务专属子流程");
        assertEquals(LEGAL_KEY, definitionKey(sub.getProcessDefinitionId()), "应启动法务部专属流程");

        // 专属流程的第一个节点是固定岗位，不需要文书选人
        Task first = singleTask(sub.getId());
        assertEquals("Task_LegalCompliance", first.getTaskDefinitionKey(), "首节点应为合规初审");
        assertEquals(1, taskService.createTaskQuery().taskId(first.getId()).taskCandidateUser("grace").count(),
                "grace 属于 legal_compliance，应是候选处理人");
        assertEquals(0, taskService.createTaskQuery().taskId(first.getId()).taskCandidateUser("alice").count(),
                "部门外人员不应成为专属流程节点的候选人");
    }

    // ==================== 4. 跨部门选流程必须被拒绝 ====================

    @Test
    void rejectsSubProcessOfAnotherDept() {
        String pid = startProcessAndApply("applicant4");
        String managerTaskId = findTask("manager", "manager_review", pid);

        Map<String, String> target = new LinkedHashMap<>();
        target.put("type", BizCommTask.TARGET_DEPT);
        target.put("id", DEPT_IT);
        target.put("name", "IT 部");
        target.put("subProcessKey", FINANCE_KEY);
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("taskId", managerTaskId);
        req.put("content", "用财务流程冒充 IT 部沟通");
        req.put("targets", List.of(target));

        Map<String, Object> resp = post("/api/comm/start", req, "manager");
        assertNotNull(resp, "发起请求无响应");
        assertEquals(Boolean.TRUE, resp.get("error"), "跨部门子流程必须被拒绝: " + resp);

        Map<String, Object> status = getMap("/api/comm/status/" + managerTaskId, "manager");
        assertEquals("NORMAL", status.get("bizStatus"), "启动失败后主待办不应被锁住: " + status);
    }

    // ==================== 辅助方法 ====================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> subProcesses(String deptId) {
        String body = rest.exchange("/api/comm/subprocesses?deptId=" + deptId, HttpMethod.GET,
                new HttpEntity<>(headers("manager")), String.class).getBody();
        try {
            return objectMapper.readValue(body, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("解析可选流程失败: " + body, e);
        }
    }

    private String deptRecordId(String managerTaskId) {
        return (String) deptRecord(managerTaskId).get("id");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deptRecord(String managerTaskId) {
        Map<String, Object> status = getMap("/api/comm/status/" + managerTaskId, "manager");
        Object value = status.get("commTasks");
        List<Map<String, Object>> records = value == null ? new ArrayList<>() : (List<Map<String, Object>>) value;
        return records.stream()
                .filter(r -> BizCommTask.TARGET_DEPT.equals(r.get("targetType")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到部门沟通记录: " + status));
    }

    private Task singleTask(String processInstanceId) {
        Task task = taskService.createTaskQuery().processInstanceId(processInstanceId).singleResult();
        assertNotNull(task, "子流程中没有活动任务: " + processInstanceId);
        return task;
    }

    private String definitionKey(String processDefinitionId) {
        return repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(processDefinitionId).singleResult().getKey();
    }

    private String startProcessAndApply(String applicant) {
        Map<String, Object> started = post("/api/comm/demo/start", null, applicant);
        String pid = (String) started.get("processInstanceId");
        assertNotNull(pid, "启动流程失败: " + started);
        String applyTaskId = findTask(applicant, "apply", pid);
        post("/api/comm/demo/complete/" + applyTaskId, null, applicant);
        return pid;
    }

    private String findTask(String assignee, String taskDefKey, String processInstanceId) {
        Task task = taskService.createTaskQuery().processInstanceId(processInstanceId)
                .taskDefinitionKey(taskDefKey).singleResult();
        assertNotNull(task, "未找到 " + taskDefKey + " 任务（" + assignee + "）");
        return task.getId();
    }

    private Map<String, Object> startDeptComm(String managerTaskId, String deptId, String deptName,
                                              String content, String subProcessKey) {
        Map<String, String> target = new LinkedHashMap<>();
        target.put("type", BizCommTask.TARGET_DEPT);
        target.put("id", deptId);
        target.put("name", deptName);
        target.put("subProcessKey", subProcessKey);

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("taskId", managerTaskId);
        req.put("content", content);
        req.put("targets", List.of(target));

        Map<String, Object> resp = post("/api/comm/start", req, "manager");
        assertNotNull(resp, "发起部门沟通无响应");
        assertFalse(Boolean.TRUE.equals(resp.get("error")), "发起部门沟通失败: " + resp);
        return resp;
    }

    private HttpHeaders headers(String user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (user != null) {
            headers.set(HttpHeaders.COOKIE, TestSession.cookie(port, user));
            headers.set("X-XSRF-TOKEN", TestSession.csrfToken(port, user));
        }
        return headers;
    }

    private Map<String, Object> post(String path, Object body, String user) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(user)),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }).getBody();
    }

    private Map<String, Object> getMap(String path, String user) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(user)),
                new ParameterizedTypeReference<Map<String, Object>>() {
                }).getBody();
    }
}
