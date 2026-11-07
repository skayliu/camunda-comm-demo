package com.example.camundademo.controller;

import com.example.camundademo.security.CurrentUser;
import com.example.camundademo.service.BusinessException;
import com.example.camundademo.service.ProcessTraceService;
import com.example.camundademo.service.TaskDelegateService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.task.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 委托接口。
 *
 * <p>与转办一致：操作人一律取认证上下文，请求体只收业务字段，
 * 避免客户端通过传 owner / operator 伪造委托来源。</p>
 */
@RestController
@RequestMapping("/api/wb")
public class TaskDelegateController {

    @Autowired
    private TaskDelegateService taskDelegateService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private ProcessTraceService processTraceService;

    /** 发起委托：仅当前办理人可操作 */
    @PostMapping("/task/{taskId}/delegate")
    public Map<String, Object> delegate(@PathVariable String taskId,
                                        @RequestBody(required = false) Map<String, Object> req,
                                        Authentication authentication) {
        String user = CurrentUser.require(authentication);
        Object target = req == null ? null : req.get("targetUserId");
        Object reason = req == null ? null : req.get("reason");
        return taskDelegateService.delegate(taskId, user,
                target == null ? null : String.valueOf(target),
                reason == null ? null : String.valueOf(reason));
    }

    /** 受托人提交受托意见并交回（resolveTask），不会结束任务 */
    @PostMapping("/task/{taskId}/delegate/resolve")
    public Map<String, Object> resolve(@PathVariable String taskId,
                                       @RequestBody(required = false) Map<String, Object> req,
                                       Authentication authentication) {
        String user = CurrentUser.require(authentication);
        Object comment = req == null ? null : req.get("comment");
        return taskDelegateService.resolve(taskId, user,
                comment == null ? null : String.valueOf(comment));
    }

    /** 委托人收回委托（受托人尚未交回时可用） */
    @PostMapping("/task/{taskId}/delegate/cancel")
    public Map<String, Object> cancel(@PathVariable String taskId,
                                      @RequestBody(required = false) Map<String, Object> req,
                                      Authentication authentication) {
        String user = CurrentUser.require(authentication);
        Object reason = req == null ? null : req.get("reason");
        return taskDelegateService.cancel(taskId, user,
                reason == null ? null : String.valueOf(reason));
    }

    /**
     * 单条待办的委托链。
     *
     * <p>可见范围：当前办理人、引擎 OWNER（委托中）、委托链上的人。
     * 委托人把任务委托出去后，需要能查到“交给谁了、对方交了没”，
     * 否则这条待办会从他的所有列表里消失。</p>
     */
    @GetMapping("/task/{taskId}/delegations")
    public List<Map<String, Object>> delegations(@PathVariable String taskId,
                                                 Authentication authentication) {
        String user = CurrentUser.require(authentication);
        List<Map<String, Object>> chain = taskDelegateService.listByTaskId(taskId);
        boolean participant = chain.stream().anyMatch(d ->
                user.equals(d.get("owner")) || user.equals(d.get("delegateUser")));
        if (!participant) {
            Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
            boolean involved = task != null
                    && (user.equals(task.getAssignee()) || user.equals(task.getOwner()));
            if (!involved) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看该待办的委托记录");
            }
        }
        return chain;
    }

    /** 某个流程实例的全部委托记录（流程跟踪、我的发起详情共用） */
    @GetMapping("/process/{processInstanceId}/delegations")
    public List<Map<String, Object>> processDelegations(@PathVariable String processInstanceId,
                                                        Authentication authentication) {
        String user = CurrentUser.require(authentication);
        if (!processTraceService.canView(processInstanceId, user)
                && !taskDelegateService.participatedInDelegate(processInstanceId, user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看该流程的委托记录");
        }
        return taskDelegateService.listByProcessInstanceId(processInstanceId);
    }

    /** 我的委托（含已交回 / 已收回） */
    @GetMapping("/delegations/out")
    public List<Map<String, Object>> delegatedOut(Authentication authentication) {
        return taskDelegateService.listByOwner(CurrentUser.require(authentication));
    }

    /** 我的受托（含已交回 / 被收回） */
    @GetMapping("/delegations/in")
    public List<Map<String, Object>> delegatedIn(Authentication authentication) {
        return taskDelegateService.listByDelegateUser(CurrentUser.require(authentication));
    }

    /**
     * 委托中条数（记录 PENDING 且任务仍在运行）。
     *
     * <p>待办中心菜单角标用：只回一个数字，不拉整段委托历史。</p>
     */
    @GetMapping("/delegations/pending-count")
    public Map<String, Object> pendingCount(Authentication authentication) {
        int count = taskDelegateService.countPendingOut(CurrentUser.require(authentication));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("count", count);
        return resp;
    }

    @ExceptionHandler(BusinessException.class)
    public Map<String, Object> handleBusiness(BusinessException e) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("error", true);
        r.put("message", e.getMessage());
        return r;
    }
}
