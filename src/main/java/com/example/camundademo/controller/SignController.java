package com.example.camundademo.controller;

import com.example.camundademo.security.CurrentUser;
import com.example.camundademo.service.BusinessException;
import com.example.camundademo.service.SignService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 加签接口：前加签 / 后加签 / 并加签，以及减签与加签任务提交。
 *
 * <p>所有“谁在操作”一律取认证上下文，请求体不接受 operator / initiator 字段。</p>
 */
@RestController
@RequestMapping("/api/sign")
public class SignController {

    @Autowired
    private SignService signService;

    /**
     * 发起前加签或并加签。
     *
     * <p>{@code sequential} 只对前加签生效；完成条件不在此接口暴露，
     * 由管理员在部署期配置（见 application.yml 的 app.add-sign.approve-mode）。</p>
     */
    @PostMapping("/add")
    public Map<String, Object> add(@RequestBody Map<String, Object> req, Authentication authentication) {
        String operator = CurrentUser.require(authentication);
        return signService.add(text(req, "taskId"), operator, text(req, "signType"),
                users(req), bool(req, "sequential"), text(req, "comment"));
    }

    /** 通过审批并后加签：先暂存意见，后置轮次结束后再真正 complete 原任务 */
    @PostMapping("/complete-post")
    public Map<String, Object> completePost(@RequestBody Map<String, Object> req, Authentication authentication) {
        String operator = CurrentUser.require(authentication);
        return signService.completeWithPostSign(text(req, "taskId"), operator,
                text(req, "comment"), boolDefault(req, "approved", true),
                users(req), bool(req, "sequential"));
    }

    /** 加签人提交（同意 / 驳回）。任一驳回即整轮不通过，引擎销毁其余待办。 */
    @PostMapping("/task/{taskId}/complete")
    public Map<String, Object> completeAddSignTask(@PathVariable String taskId,
                                                   @RequestBody(required = false) Map<String, Object> req,
                                                   Authentication authentication) {
        String operator = CurrentUser.require(authentication);
        return signService.completeAddSignTask(taskId, operator,
                boolDefault(req, "approved", true), text(req, "comment"));
    }

    /** 减签：只能减还没提交意见的人，减完可以补人重加 */
    @PostMapping("/{recordId}/remove")
    public Map<String, Object> remove(@PathVariable String recordId, Authentication authentication) {
        String operator = CurrentUser.require(authentication);
        return signService.remove(recordId, operator);
    }

    /** 某个待办的全部加签记录（任务详情展示） */
    @GetMapping("/task/{taskId}")
    public List<Map<String, Object>> byTask(@PathVariable String taskId, Authentication authentication) {
        CurrentUser.require(authentication);
        return signService.listByTaskId(taskId);
    }

    /** 某个流程实例的全部加签记录（流程跟踪 / 已办详情） */
    @GetMapping("/process/{processInstanceId}")
    public List<Map<String, Object>> byProcess(@PathVariable String processInstanceId,
                                               Authentication authentication) {
        CurrentUser.require(authentication);
        return signService.listByProcessInstanceId(processInstanceId);
    }

    @ExceptionHandler(BusinessException.class)
    public Map<String, Object> handleBusiness(BusinessException e) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("error", true);
        r.put("message", e.getMessage());
        return r;
    }

    private String text(Map<String, Object> req, String key) {
        Object value = req == null ? null : req.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private boolean bool(Map<String, Object> req, String key) {
        return boolDefault(req, key, false);
    }

    private boolean boolDefault(Map<String, Object> req, String key, boolean fallback) {
        Object value = req == null ? null : req.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private List<String> users(Map<String, Object> req) {
        Object raw = req == null ? null : req.get("users");
        List<String> result = new ArrayList<>();
        if (raw instanceof List) {
            for (Object item : (List<Object>) raw) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
        }
        return result;
    }
}
