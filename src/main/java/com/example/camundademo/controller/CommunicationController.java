package com.example.camundademo.controller;

import com.example.camundademo.entity.BizCommTask;
import com.example.camundademo.security.CurrentUser;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * 沟通功能REST接口
 *
 * <p><b>身份来源</b>：所有涉及“谁在操作”的字段一律取自认证上下文
 * （{@link CurrentUser#require(Authentication)}），请求体里的 userId / initiator /
 * operator 字段不再被读取。客户端可以随便填这些字段，服务端不能信。</p>
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

    @Autowired
    private org.camunda.bpm.engine.IdentityService identityService;

    // ===== 流程演示辅助接口 =====

    /** 启动流程：申请人 = 当前登录用户 */
    @PostMapping("/demo/start")
    public Map<String, Object> startProcess(Authentication authentication) {
        String initiator = CurrentUser.require(authentication);
        Map<String, Object> vars = new HashMap<>();
        vars.put("applicant", initiator);
        // 记录发起人，历史表中 START_USER_ID_ 才有值，「我的发起」才能查得到
        identityService.setAuthenticatedUserId(initiator);
        try {
            ProcessInstance pi = runtimeService.startProcessInstanceByKey("approval", vars);
            return Collections.singletonMap("processInstanceId", pi.getId());
        } finally {
            identityService.clearAuthentication();
        }
    }

    /** 可选沟通对象（前端选择器用）：只返回账号与显示名，不暴露密码等敏感信息 */
    @GetMapping("/targets")
    public List<Map<String, Object>> targets(@RequestParam(required = false) String keyword,
                                             Authentication authentication) {
        String currentUser = CurrentUser.require(authentication);
        String kw = keyword == null ? "" : keyword.trim();
        List<Map<String, Object>> result = new ArrayList<>();
        List<org.camunda.bpm.engine.identity.User> users = identityService.createUserQuery()
                .userFirstNameLike("%" + kw + "%")
                .listPage(0, 50);
        for (org.camunda.bpm.engine.identity.User u : users) {
            if (u.getId().equals(currentUser)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("name", u.getFirstName() == null ? u.getId() : u.getFirstName());
            result.add(m);
        }
        return result;
    }

    /** 查看任务列表：默认查自己；显式查他人需与当前用户一致，避免越权浏览待办 */
    @GetMapping("/demo/tasks")
    public List<Map<String, Object>> tasks(@RequestParam(value = "assignee", required = false) String assignee,
                                           Authentication authentication) {
        String currentUser = CurrentUser.require(authentication);
        String queryAssignee = (assignee == null || assignee.isBlank()) ? currentUser : assignee;
        if (!queryAssignee.equals(currentUser)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能查询自己的待办");
        }

        List<Task> tasks = taskService.createTaskQuery().taskAssignee(queryAssignee).list();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Task t : tasks) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskId", t.getId());
            m.put("taskDefKey", t.getTaskDefinitionKey());
            // 沟通任务的 key 形如「源节点key#COMM」，前端用布尔字段判断更稳妥
            m.put("isCommTask", BizCommTask.isCommTaskKey(t.getTaskDefinitionKey()));
            m.put("name", t.getName());
            m.put("processInstanceId", t.getProcessInstanceId());
            m.put("assignee", t.getAssignee());
            m.put("parentTaskId", t.getParentTaskId());
            result.add(m);
        }
        return result;
    }

    /** 完成任务（审批提交）：只能完成分派给自己的任务；可选审批意见 */
    @PostMapping("/demo/complete/{taskId}")
    public Map<String, Object> complete(@PathVariable String taskId,
                                        @RequestBody(required = false) Map<String, Object> req,
                                        Authentication authentication) {
        String currentUser = CurrentUser.require(authentication);

        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) {
            // 保留 5xx 语义：任务不存在是真实故障，不是业务提示
            throw new IllegalStateException("任务不存在: " + taskId);
        }
        if (task.getAssignee() != null && !task.getAssignee().equals(currentUser)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "不能完成他人的待办");
        }

        Object comment = req == null ? null : req.get("comment");
        try {
            if (comment != null && !String.valueOf(comment).isBlank()) {
                taskService.createComment(taskId, currentUser, String.valueOf(comment));
            }
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
    public Map<String, Object> finished(@PathVariable String pid, Authentication authentication) {
        CurrentUser.require(authentication);
        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(pid).singleResult();
        return Collections.singletonMap("finished", hpi != null && hpi.getEndTime() != null);
    }

    // ===== 沟通核心接口 =====

    /** 发起沟通：发起人取当前登录用户 */
    @PostMapping("/start")
    public Map<String, Object> start(@RequestBody Map<String, Object> req, Authentication authentication) {
        String initiator = CurrentUser.require(authentication);
        String taskId = (String) req.get("taskId");
        String content = (String) req.get("content");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> targets = (List<Map<String, String>>) req.get("targets");
        String sessionId = communicationService.startCommunication(taskId, initiator, content, targets);
        return Collections.singletonMap("sessionId", sessionId);
    }

    /** 查询待办沟通状态：按当前登录用户计算 canInitiate / canWithdraw */
    @GetMapping("/status/{taskId}")
    public Map<String, Object> status(@PathVariable String taskId, Authentication authentication) {
        String currentUser = CurrentUser.require(authentication);
        return communicationService.getTaskCommStatus(taskId, currentUser);
    }

    /** 收回沟通（commTaskIds为空=全部收回）：操作人取当前登录用户 */
    @PostMapping("/withdraw/{sessionId}")
    public Map<String, Object> withdraw(@PathVariable String sessionId,
                                        @RequestBody(required = false) Map<String, Object> req,
                                        Authentication authentication) {
        String operator = CurrentUser.require(authentication);
        @SuppressWarnings("unchecked")
        List<String> commTaskIds = req == null ? null : (List<String>) req.get("commTaskIds");
        communicationService.withdrawCommunication(sessionId, operator, commTaskIds);
        return Collections.singletonMap("result", "ok");
    }

    /** 反馈沟通意见：反馈人取当前登录用户 */
    @PostMapping("/reply")
    public Map<String, Object> reply(@RequestBody Map<String, Object> req, Authentication authentication) {
        String userId = CurrentUser.require(authentication);
        String commTaskId = (String) req.get("commTaskRecordId");
        String content = (String) req.get("content");
        communicationService.replyCommTask(commTaskId, userId, content);
        return Collections.singletonMap("result", "ok");
    }

    /** 继续沟通：追问人取当前登录用户 */
    @PostMapping("/continue")
    public Map<String, Object> continueComm(@RequestBody Map<String, Object> req, Authentication authentication) {
        String userId = CurrentUser.require(authentication);
        String parentCommTaskId = (String) req.get("parentCommTaskRecordId");
        String content = (String) req.get("content");
        @SuppressWarnings("unchecked")
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
