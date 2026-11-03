package com.example.camundademo.controller;

import com.example.camundademo.security.CurrentUser;
import com.example.camundademo.service.ProcessTraceService;
import com.example.camundademo.service.WorkbenchService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.task.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
