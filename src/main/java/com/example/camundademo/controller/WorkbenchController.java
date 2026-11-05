package com.example.camundademo.controller;

import com.example.camundademo.security.CurrentUser;
import com.example.camundademo.service.BusinessException;
import com.example.camundademo.service.ProcessTraceService;
import com.example.camundademo.service.TaskTransferService;
import com.example.camundademo.service.WorkbenchService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 待办中心接口：我的待办 / 已办 / 我的发起 / 待办详情。
 *
 * <p>所有查询都以认证上下文中的当前用户为准，不接受任何“查别人”的参数。</p>
 */
@RestController
@RequestMapping("/api/wb")
public class WorkbenchController {

    @Autowired
    private WorkbenchService workbenchService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private ProcessTraceService processTraceService;

    @Autowired
    private TaskTransferService taskTransferService;

    /** 当前登录用户信息（前端登录后填充头像区/权限区） */
    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        return workbenchService.currentUserInfo(CurrentUser.require(authentication));
    }

    /**
     * 我的待办
     *
     * @param type all / approval（仅审批待办）/ comm（仅沟通待办）
     */
    @GetMapping("/todo")
    public Map<String, Object> todo(@RequestParam(required = false) String keyword,
                                    @RequestParam(required = false, defaultValue = "all") String type,
                                    @RequestParam(required = false, defaultValue = "1") int page,
                                    @RequestParam(required = false, defaultValue = "10") int size,
                                    Authentication authentication) {
        String user = CurrentUser.require(authentication);
        return workbenchService.listTodo(user, keyword, type, page, size);
    }

    /** 我的已办 */
    @GetMapping("/done")
    public Map<String, Object> done(@RequestParam(required = false) String keyword,
                                    @RequestParam(required = false, defaultValue = "1") int page,
                                    @RequestParam(required = false, defaultValue = "10") int size,
                                    Authentication authentication) {
        String user = CurrentUser.require(authentication);
        return workbenchService.listDone(user, keyword, page, size);
    }

    /** 我的发起 */
    @GetMapping("/started")
    public Map<String, Object> started(@RequestParam(required = false) String keyword,
                                       @RequestParam(required = false, defaultValue = "1") int page,
                                       @RequestParam(required = false, defaultValue = "10") int size,
                                       Authentication authentication) {
        String user = CurrentUser.require(authentication);
        return workbenchService.listStarted(user, keyword, page, size);
    }

    /** 待办详情（含流程变量、沟通状态、审批意见） */
    @GetMapping("/task/{taskId}")
    public Map<String, Object> taskDetail(@PathVariable String taskId, Authentication authentication) {
        String user = CurrentUser.require(authentication);
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在或已被处理");
        }
        if (task.getAssignee() != null && !task.getAssignee().equals(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "不能查看他人的待办");
        }
        return workbenchService.taskDetail(taskId, user);
    }

    /**
     * 转办：把待办交给另一个人办理。
     *
     * <p>操作人一律取认证上下文；请求体只有 {@code targetUserId} 与 {@code reason}，
     * 避免客户端通过传 fromUser 之类的字段伪造转办来源。</p>
     */
    @PostMapping("/task/{taskId}/transfer")
    public Map<String, Object> transfer(@PathVariable String taskId,
                                        @RequestBody(required = false) Map<String, Object> req,
                                        Authentication authentication) {
        String user = CurrentUser.require(authentication);
        Object target = req == null ? null : req.get("targetUserId");
        Object reason = req == null ? null : req.get("reason");
        return taskTransferService.transfer(taskId, user,
                target == null ? null : String.valueOf(target),
                reason == null ? null : String.valueOf(reason));
    }

    /**
     * 单条待办的转办链。
     *
     * <p>可见范围除当前办理人外，还包括转办链上的人员——转出方需要能查到“我把这活交给谁了”，
     * 否则转办一提交，这条待办就从他的所有列表里消失。</p>
     */
    @GetMapping("/task/{taskId}/transfers")
    public List<Map<String, Object>> transfers(@PathVariable String taskId,
                                               Authentication authentication) {
        String user = CurrentUser.require(authentication);
        List<Map<String, Object>> chain = taskTransferService.listByTaskId(taskId);
        boolean participant = chain.stream().anyMatch(t ->
                user.equals(t.get("from")) || user.equals(t.get("to")));
        if (!participant) {
            Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
            if (task == null || !user.equals(task.getAssignee())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看该待办的转办记录");
            }
        }
        return chain;
    }

    /**
     * 某个流程实例的全部转办记录（「我的发起」「我的已办」详情共用）。
     *
     * <p>可见范围与流程跟踪保持一致：发起人、办理过该实例任务的人、转办链上的人。</p>
     */
    @GetMapping("/process/{processInstanceId}/transfers")
    public List<Map<String, Object>> processTransfers(@PathVariable String processInstanceId,
                                                      Authentication authentication) {
        String user = CurrentUser.require(authentication);
        if (!processTraceService.canView(processInstanceId, user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看该流程的转办记录");
        }
        return taskTransferService.listByProcessInstanceId(processInstanceId);
    }

    /**
     * 业务异常统一返回 200 + {error:true, message}，与沟通接口口径一致。
     * 不加这个处理器，BusinessException 会落到 500，前端只能提示“服务端处理失败”。
     */
    @ExceptionHandler(BusinessException.class)
    public Map<String, Object> handleBusiness(BusinessException e) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("error", true);
        r.put("message", e.getMessage());
        return r;
    }

    /**
     * 流程跟踪：BPMN 节点几何 + 每个节点的执行状态与执行明细（含沟通任务）。
     *
     * <p>可见范围与任务详情一致：只有发起人、办理过该实例任务的人以及当前待办处理人可查看。</p>
     */
    @GetMapping("/trace/{processInstanceId}")
    public Map<String, Object> trace(@PathVariable String processInstanceId, Authentication authentication) {
        String user = CurrentUser.require(authentication);
        if (!processTraceService.canView(processInstanceId, user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看该流程的跟踪信息");
        }
        return processTraceService.trace(processInstanceId);
    }
}
