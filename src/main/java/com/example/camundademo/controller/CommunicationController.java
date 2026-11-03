package com.example.camundademo.controller;

import com.example.camundademo.service.BusinessException;
import com.example.camundademo.service.CommunicationLockedException;
import com.example.camundademo.service.CommunicationService;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 沟通功能REST接口
 */
@RestController
@RequestMapping("/api/comm")
public class CommunicationController {

    @Autowired
    private CommunicationService communicationService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private HistoryService historyService;

    // ===== 流程演示辅助接口 =====

    /** 启动流程 */
    @PostMapping("/demo/start")
    public Map<String, Object> startProcess(@RequestParam(value = "initiator", defaultValue = "applicant") String initiator) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("applicant", initiator);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey("approval", vars);
        return Collections.singletonMap("processInstanceId", pi.getId());
    }

    /** 查看任务列表（按人） */
    @GetMapping("/demo/tasks")
    public List<Map<String, Object>> tasks(@RequestParam("assignee") String assignee) {
        List<Task> tasks = taskService.createTaskQuery().taskAssignee(assignee).list();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Task t : tasks) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskId", t.getId());
            m.put("taskDefKey", t.getTaskDefinitionKey());
            m.put("name", t.getName());
            m.put("processInstanceId", t.getProcessInstanceId());
            m.put("assignee", t.getAssignee());
            m.put("parentTaskId", t.getParentTaskId());
            result.add(m);
        }
        return result;
    }

    /** 完成任务（审批提交） */
    @PostMapping("/demo/complete/{taskId}")
    public Map<String, Object> complete(@PathVariable String taskId) {
        try {
            taskService.complete(taskId);
            return Collections.singletonMap("result", "ok");
        } catch (RuntimeException e) {
            // 只把“沟通中拦截”翻译成业务响应；其余异常原样抛出，让接口返回 5xx。
            // 原实现 catch(Exception) 会把手写错误（变量缺失、任务不存在等）也伪装成
            // 200 + error:true，前端无法区分“正常拦截”和“流程真的坏了”。
            CommunicationLockedException locked = findLocked(e);
            if (locked == null) {
                throw e;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("error", true);
            r.put("code", CommunicationLockedException.CODE);
            r.put("message", locked.getMessage());
            return r;
        }
    }

    /** 沿 cause 链查找沟通锁定异常（Camunda / Spring 事务可能对异常做包装） */
    private CommunicationLockedException findLocked(Throwable e) {
        Throwable cur = e;
        for (int i = 0; i < 10 && cur != null; i++) {
            if (cur instanceof CommunicationLockedException) {
                return (CommunicationLockedException) cur;
            }
            if (cur.getCause() == cur) {
                break;
            }
            cur = cur.getCause();
        }
        return null;
    }

    /** 流程是否结束 */
    @GetMapping("/demo/finished/{pid}")
    public Map<String, Object> finished(@PathVariable String pid) {
        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(pid).singleResult();
        return Collections.singletonMap("finished", hpi != null && hpi.getEndTime() != null);
    }

    // ===== 沟通核心接口 =====

    /** 发起沟通 */
    @PostMapping("/start")
    public Map<String, Object> start(@RequestBody Map<String, Object> req) {
        String taskId = (String) req.get("taskId");
        String initiator = (String) req.get("initiator");
        String content = (String) req.get("content");
        List<Map<String, String>> targets = (List<Map<String, String>>) req.get("targets");
        String sessionId = communicationService.startCommunication(taskId, initiator, content, targets);
        return Collections.singletonMap("sessionId", sessionId);
    }

    /** 查询待办沟通状态 */
    @GetMapping("/status/{taskId}")
    public Map<String, Object> status(@PathVariable String taskId,
                                      @RequestParam(value = "userId", defaultValue = "") String userId) {
        return communicationService.getTaskCommStatus(taskId, userId);
    }

    /** 收回沟通（commTaskIds为空=全部收回） */
    @PostMapping("/withdraw/{sessionId}")
    public Map<String, Object> withdraw(@PathVariable String sessionId,
                                        @RequestBody Map<String, Object> req) {
        String operator = (String) req.get("operator");
        List<String> commTaskIds = (List<String>) req.get("commTaskIds");
        communicationService.withdrawCommunication(sessionId, operator, commTaskIds);
        return Collections.singletonMap("result", "ok");
    }

    /** 反馈沟通意见 */
    @PostMapping("/reply")
    public Map<String, Object> reply(@RequestBody Map<String, Object> req) {
        String commTaskId = (String) req.get("commTaskRecordId");
        String userId = (String) req.get("userId");
        String content = (String) req.get("content");
        communicationService.replyCommTask(commTaskId, userId, content);
        return Collections.singletonMap("result", "ok");
    }

    /** 继续沟通 */
    @PostMapping("/continue")
    public Map<String, Object> continueComm(@RequestBody Map<String, Object> req) {
        String parentCommTaskId = (String) req.get("parentCommTaskRecordId");
        String userId = (String) req.get("userId");
        String content = (String) req.get("content");
        List<Map<String, String>> targets = (List<Map<String, String>>) req.get("targets");
        communicationService.continueCommunication(parentCommTaskId, userId, content, targets);
        return Collections.singletonMap("result", "ok");
    }

    // ===== 异常处理 =====
    @ExceptionHandler(BusinessException.class)
    public Map<String, Object> handle(BusinessException e) {
        Map<String, Object> r = new HashMap<>();
        r.put("error", true);
        r.put("message", e.getMessage());
        return r;
    }
}
