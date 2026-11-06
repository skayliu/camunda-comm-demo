package com.example.camundademo.controller;

import com.example.camundademo.security.CurrentUser;
import com.example.camundademo.service.AgentRuleService;
import com.example.camundademo.service.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 代理规则接口。
 *
 * <p>规则只能由本人设置（owner 永远取认证上下文）；
 * 管理员可以强制停用，但不能修改或替他人启用。</p>
 */
@RestController
@RequestMapping("/api/agent")
public class AgentRuleController {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_TIME_MIN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Autowired
    private AgentRuleService agentRuleService;

    /** 我的代理规则（含状态：ACTIVE / UPCOMING / EXPIRED / DISABLED） */
    @GetMapping("/rules")
    public List<Map<String, Object>> rules(Authentication authentication) {
        return agentRuleService.listMine(CurrentUser.require(authentication));
    }

    /** 管理员视图：全部规则（只读，停用仍走 /rules/{id}/disable） */
    @GetMapping("/rules/all")
    public List<Map<String, Object>> allRules(Authentication authentication) {
        return agentRuleService.listAll(CurrentUser.require(authentication));
    }

    /**
     * 新建或修改规则。
     *
     * <p>请求体：{@code id}（修改时传）、{@code agentUserId}、{@code startTime}、{@code endTime}、
     * {@code processDefinitionKeys}（逗号分隔，空=全部流程）。</p>
     */
    @PostMapping("/rules")
    public Map<String, Object> save(@RequestBody Map<String, Object> req, Authentication authentication) {
        String user = CurrentUser.require(authentication);
        return agentRuleService.save(
                str(req.get("id")),
                user,
                str(req.get("agentUserId")),
                parseDate(req.get("startTime"), "startTime"),
                parseDate(req.get("endTime"), "endTime"),
                str(req.get("processDefinitionKeys")),
                str(req.get("delegateMode")));
    }

    /**
     * 停用规则。
     *
     * <p>请求体可选：{@code {"cancelActive": true}} 表示同时收回该规则下仍在委托中的任务。
     * 不传（或 false）时保持原语义——只影响后续新任务，不动代理人手里的待办。</p>
     */
    @PostMapping("/rules/{id}/disable")
    public Map<String, Object> disable(@PathVariable String id,
                                       @RequestBody(required = false) Map<String, Object> req,
                                       Authentication authentication) {
        boolean cancelActive = req != null && flag(req.get("cancelActive"));
        return agentRuleService.disable(id, CurrentUser.require(authentication), cancelActive);
    }

    @PostMapping("/rules/{id}/enable")
    public Map<String, Object> enable(@PathVariable String id, Authentication authentication) {
        return agentRuleService.enable(id, CurrentUser.require(authentication));
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 布尔开关宽松解析：兼容 true / "true" / 1 */
    private boolean flag(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    /**
     * 时间解析：同时接受毫秒时间戳、ISO-8601、{@code yyyy-MM-dd HH:mm[:ss]} 与 {@code yyyy-MM-dd}。
     *
     * <p>前端日期组件、curl、测试三种调用方式各用不同格式，接口层统一收口，
     * 避免把“格式不对”变成 500。</p>
     */
    private java.util.Date parseDate(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return new java.util.Date(number.longValue());
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new java.util.Date(Long.parseLong(text));
        } catch (NumberFormatException ignored) {
            // 不是时间戳，继续按字符串解析
        }
        try {
            return java.util.Date.from(OffsetDateTime.parse(text).toInstant());
        } catch (RuntimeException ignored) {
            // 继续
        }
        for (DateTimeFormatter formatter : new DateTimeFormatter[]{DATE_TIME, DATE_TIME_MIN}) {
            try {
                return java.util.Date.from(LocalDateTime.parse(text, formatter)
                        .atZone(ZoneId.systemDefault()).toInstant());
            } catch (RuntimeException ignored) {
                // 继续
            }
        }
        try {
            return java.util.Date.from(LocalDate.parse(text, DATE)
                    .atStartOfDay(ZoneId.systemDefault()).toInstant());
        } catch (RuntimeException e) {
            throw new BusinessException(field + " 时间格式不正确，应为 yyyy-MM-dd HH:mm:ss 或时间戳");
        }
    }

    @ExceptionHandler(BusinessException.class)
    public Map<String, Object> handleBusiness(BusinessException e) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("error", true);
        r.put("message", e.getMessage());
        return r;
    }
}
