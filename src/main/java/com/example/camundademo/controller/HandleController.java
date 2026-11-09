package com.example.camundademo.controller;

import com.example.camundademo.security.CurrentUser;
import com.example.camundademo.service.BusinessException;
import com.example.camundademo.service.HandleService;
import com.example.camundademo.service.ProcessTraceService;
import org.camunda.bpm.engine.IdentityService;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阅办（承办）REST 接口。
 *
 * <p>阅办人没有任何审批权限：只有“开始办理”“提交结果”两个动作，
 * 不提供同意/拒绝/转办/加签入口。</p>
 */
@RestController
@RequestMapping("/api/handle")
public class HandleController {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]");

    @Autowired
    private HandleService handleService;

    @Autowired
    private ProcessTraceService processTraceService;

    @Autowired
    private IdentityService identityService;

    /** 可选承办人 */
    @GetMapping("/users")
    public List<Map<String, Object>> users(@RequestParam(required = false) String keyword,
                                           Authentication authentication) {
        String currentUser = CurrentUser.require(authentication);
        String kw = keyword == null ? "" : keyword.trim();
        List<Map<String, Object>> result = new ArrayList<>();
        for (org.camunda.bpm.engine.identity.User user : identityService.createUserQuery()
                .userFirstNameLike("%" + kw + "%").listPage(0, 50)) {
            if (user.getId().equals(currentUser)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", user.getId());
            item.put("name", user.getFirstName() == null ? user.getId() : user.getFirstName());
            result.add(item);
        }
        return result;
    }

    /**
     * 发起阅办。
     * 请求体：{ processInstanceId, taskId?, assigneeId, instruction?, deadline? }
     * deadline 支持 ISO 时间串、yyyy-MM-dd HH:mm 或毫秒时间戳。
     */
    @PostMapping
    public Map<String, Object> add(@RequestBody Map<String, Object> req, Authentication authentication) {
        String sender = CurrentUser.require(authentication);
        String processInstanceId = str(req.get("processInstanceId"));
        String taskId = str(req.get("taskId"));
        String assigneeId = str(req.get("assigneeId"));
        String instruction = str(req.get("instruction"));
        Date deadline = parseDate(req.get("deadline"));
        return handleService.addHandle(processInstanceId, taskId, sender, assigneeId, instruction, deadline);
    }

    /** 我的承办：status = TODO / PENDING / HANDLING / OVERDUE / DONE / ALL */
    @GetMapping("/mine")
    public Map<String, Object> mine(@RequestParam(required = false, defaultValue = "TODO") String status,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false, defaultValue = "1") int page,
                                    @RequestParam(required = false, defaultValue = "10") int size,
                                    Authentication authentication) {
        String userId = CurrentUser.require(authentication);
        return handleService.listMine(userId, status, keyword, page, size);
    }

    /**
     * 我发出的阅办：status = ALL / PENDING / HANDLING / OVERDUE / DONE / REVOKED。
     * 发起人只从认证上下文取，不接受前端传 sender。
     */
    @GetMapping("/sent")
    public Map<String, Object> sent(@RequestParam(required = false, defaultValue = "ALL") String status,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false, defaultValue = "1") int page,
                                    @RequestParam(required = false, defaultValue = "10") int size,
                                    Authentication authentication) {
        String userId = CurrentUser.require(authentication);
        return handleService.listSent(userId, status, keyword, page, size);
    }

    /** 待承办数量（菜单角标） */
    @GetMapping("/pending-count")
    public Map<String, Object> pendingCount(Authentication authentication) {
        String userId = CurrentUser.require(authentication);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", handleService.countPending(userId));
        return result;
    }

    /** 开始办理：待阅 → 办理中 */
    @PostMapping("/{id}/start")
    public Map<String, Object> start(@PathVariable String id, Authentication authentication) {
        String userId = CurrentUser.require(authentication);
        handleService.start(id, userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", "ok");
        return result;
    }

    /** 提交办理结果 */
    @PostMapping("/{id}/submit")
    public Map<String, Object> submit(@PathVariable String id,
                                      @RequestBody(required = false) Map<String, Object> req,
                                      Authentication authentication) {
        String userId = CurrentUser.require(authentication);
        String resultText = req == null ? null : str(req.get("result"));
        handleService.submitResult(id, userId, resultText);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", "ok");
        return result;
    }

    /** 发起人撤回阅办 */
    @PostMapping("/{id}/revoke")
    public Map<String, Object> revoke(@PathVariable String id, Authentication authentication) {
        String userId = CurrentUser.require(authentication);
        handleService.revoke(id, userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", "ok");
        return result;
    }

    /** 某流程实例的阅办记录：可见范围与流程跟踪一致 */
    @GetMapping("/process/{processInstanceId}")
    public List<Map<String, Object>> processRecords(@PathVariable String processInstanceId,
                                                    Authentication authentication) {
        String userId = CurrentUser.require(authentication);
        if (!processTraceService.canView(processInstanceId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看该流程的阅办记录");
        }
        return handleService.listByProcess(processInstanceId, userId);
    }

    @ExceptionHandler(BusinessException.class)
    public Map<String, Object> handleBusiness(BusinessException e) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", true);
        result.put("message", e.getMessage());
        return result;
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Date parseDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return new Date(number.longValue());
        }
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return Date.from(Instant.parse(raw));
        } catch (RuntimeException ignored) {
            // 继续尝试本地日期格式
        }
        try {
            if (raw.length() <= 10) {
                LocalDate date = LocalDate.parse(raw);
                return Date.from(date.atTime(18, 0)
                        .atZone(ZoneId.systemDefault()).toInstant());
            }
            return Date.from(LocalDateTime.parse(raw, DATE_TIME)
                    .atZone(ZoneId.systemDefault()).toInstant());
        } catch (RuntimeException e) {
            throw new BusinessException("办理时限格式不正确，应为 yyyy-MM-dd HH:mm");
        }
    }
}
