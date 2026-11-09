package com.example.camundademo.controller;

import com.example.camundademo.entity.BizSubProcessDef;
import com.example.camundademo.security.CurrentUser;
import com.example.camundademo.service.BusinessException;
import com.example.camundademo.service.DeptSubTaskService;
import com.example.camundademo.service.SubProcessDefService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 部门沟通子流程接口：注册表可选列表 + 子流程待办办理。
 *
 * <p>所有「谁在操作」都取自认证上下文，请求体里的 userId 不被读取。</p>
 */
@RestController
@RequestMapping("/api/comm")
public class DeptSubProcessController {

    private final SubProcessDefService subProcessDefService;
    private final DeptSubTaskService deptSubTaskService;

    public DeptSubProcessController(SubProcessDefService subProcessDefService,
                                    DeptSubTaskService deptSubTaskService) {
        this.subProcessDefService = subProcessDefService;
        this.deptSubTaskService = deptSubTaskService;
    }

    /**
     * 某部门可选的沟通子流程。
     *
     * <p>返回通用流程 + 该部门专属流程；前端据此渲染「部门沟通」弹窗里的流程选择。</p>
     */
    @GetMapping("/subprocesses")
    public List<Map<String, Object>> subProcesses(@RequestParam(required = false) String deptId,
                                                  Authentication authentication) {
        CurrentUser.require(authentication);
        List<Map<String, Object>> result = new ArrayList<>();
        for (BizSubProcessDef d : subProcessDefService.listAvailable(deptId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("processKey", d.getProcessKey());
            m.put("processName", d.getProcessName());
            m.put("deptId", d.getDeptId());
            m.put("deptName", d.getDeptName());
            m.put("bizType", d.getBizType());
            m.put("version", d.getVersion());
            m.put("description", d.getDescription());
            m.put("general", d.isGeneral());
            result.add(m);
        }
        return result;
    }

    /** 部门子流程待办详情：前端按 nodeType 渲染分发 / 经办 / 汇总面板 */
    @GetMapping("/dept-sub/{taskId}")
    public Map<String, Object> detail(@PathVariable String taskId, Authentication authentication) {
        String userId = CurrentUser.require(authentication);
        return deptSubTaskService.detail(taskId, userId);
    }

    /** 文书分发：选择本部门经办人与意见规则 */
    @PostMapping("/dept-sub/{taskId}/dispatch")
    public Map<String, Object> dispatch(@PathVariable String taskId,
                                        @RequestBody Map<String, Object> req,
                                        Authentication authentication) {
        String userId = CurrentUser.require(authentication);
        return deptSubTaskService.dispatch(taskId, userId,
                stringList(req.get("handlerUserIds")),
                (String) req.get("opinionRule"),
                (String) req.get("remark"));
    }

    /** 通用流程：经办人提交意见（可退回文书重新分发） */
    @PostMapping("/dept-sub/{taskId}/opinion")
    public Map<String, Object> opinion(@PathVariable String taskId,
                                       @RequestBody Map<String, Object> req,
                                       Authentication authentication) {
        String userId = CurrentUser.require(authentication);
        boolean returnToClerk = Boolean.TRUE.equals(req.get("returnToClerk"));
        return deptSubTaskService.submitOpinion(taskId, userId,
                (String) req.get("opinion"),
                (String) req.get("attachments"),
                returnToClerk);
    }

    /** 专属流程：中间审批节点意见 */
    @PostMapping("/dept-sub/{taskId}/node-opinion")
    public Map<String, Object> nodeOpinion(@PathVariable String taskId,
                                           @RequestBody Map<String, Object> req,
                                           Authentication authentication) {
        String userId = CurrentUser.require(authentication);
        return deptSubTaskService.submitNodeOpinion(taskId, userId, (String) req.get("opinion"));
    }

    /** 汇总节点：提交 / 退回补充 / 重新分发 */
    @PostMapping("/dept-sub/{taskId}/summary")
    public Map<String, Object> summary(@PathVariable String taskId,
                                       @RequestBody Map<String, Object> req,
                                       Authentication authentication) {
        String userId = CurrentUser.require(authentication);
        return deptSubTaskService.submitSummary(taskId, userId,
                (String) req.get("finalOpinion"),
                (String) req.get("actionType"),
                stringList(req.get("returnHandlerIds")));
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        List<String> list = new ArrayList<>();
        if (value instanceof List<?> l) {
            for (Object o : l) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    list.add(String.valueOf(o));
                }
            }
        } else if (value instanceof String s && !s.isBlank()) {
            for (String part : s.split(",")) {
                if (!part.isBlank()) {
                    list.add(part.trim());
                }
            }
        }
        return list;
    }

    @ExceptionHandler(BusinessException.class)
    public Map<String, Object> handle(BusinessException e) {
        Map<String, Object> r = new HashMap<>();
        r.put("error", true);
        r.put("message", e.getMessage());
        return r;
    }
}
