package com.example.camundademo.controller;

import com.example.camundademo.security.CurrentUser;
import com.example.camundademo.service.BusinessException;
import com.example.camundademo.service.CcService;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 传阅 REST 接口。
 *
 * <p>所有“谁在操作”一律取认证上下文，请求体里的 sender 字段不被读取。
 * 待阅人没有任何审批权限：这里不提供 approve / reject / transfer / delegate 入口。</p>
 */
@RestController
@RequestMapping("/api/cc")
public class CcController {

    @Autowired
    private CcService ccService;

    @Autowired
    private ProcessTraceService processTraceService;

    @Autowired
    private IdentityService identityService;

    /** 可选待阅人（只返回账号与显示名） */
    @GetMapping("/targets")
    public List<Map<String, Object>> targets(@RequestParam(required = false) String keyword,
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
     * 动态加传阅。
     * 请求体：{ processInstanceId, taskId?, userIds:[], ccType?, note? }
     *
     * <p>note 是单向附言：随传阅一起送达，待阅人只能看，不能回复。</p>
     */
    @PostMapping("/add")
    public Map<String, Object> add(@RequestBody Map<String, Object> req, Authentication authentication) {
        String sender = CurrentUser.require(authentication);
        String processInstanceId = str(req.get("processInstanceId"));
        String taskId = str(req.get("taskId"));
        String ccType = str(req.get("ccType"));
        String note = str(req.get("note"));
        List<String> userIds = new ArrayList<>();
        Object rawUsers = req.get("userIds");
        if (rawUsers instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    userIds.add(String.valueOf(item));
                }
            }
        }
        return ccService.addCc(processInstanceId, taskId, sender, userIds, ccType, note);
    }

    /** 标记单条已读 */
    @PostMapping("/{id}/read")
    public Map<String, Object> markRead(@PathVariable String id, Authentication authentication) {
        String userId = CurrentUser.require(authentication);
        boolean changed = ccService.markRead(id, userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", "ok");
        result.put("changed", changed);
        return result;
    }

    /** 批量标记已读 */
    @PostMapping("/read-batch")
    public Map<String, Object> batchMarkRead(@RequestBody(required = false) Map<String, Object> req,
                                             Authentication authentication) {
        String userId = CurrentUser.require(authentication);
        List<String> ids = new ArrayList<>();
        if (req != null && req.get("ids") instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    ids.add(String.valueOf(item));
                }
            }
        }
        int updated = ccService.batchMarkRead(ids, userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", "ok");
        result.put("updated", updated);
        return result;
    }

    /** 撤回传阅（仅发送人、对方未读） */
    @PostMapping("/{id}/revoke")
    public Map<String, Object> revoke(@PathVariable String id, Authentication authentication) {
        String userId = CurrentUser.require(authentication);
        ccService.revoke(id, userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", "ok");
        return result;
    }

    /** 我的待阅：status = UNREAD / READ / REVOKED / ALL */
    @GetMapping("/mine")
    public Map<String, Object> mine(@RequestParam(required = false, defaultValue = "UNREAD") String status,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false, defaultValue = "1") int page,
                                    @RequestParam(required = false, defaultValue = "10") int size,
                                    Authentication authentication) {
        String userId = CurrentUser.require(authentication);
        return ccService.listMine(userId, status, keyword, page, size);
    }

    /**
     * 我发出的传阅：status = ALL / UNREAD / READ / REVOKED。
     *
     * <p>发送人固定取认证上下文，不接受前端传 sender 参数——
     * 否则任何人都能查到别人发出去的传阅。</p>
     */
    @GetMapping("/sent")
    public Map<String, Object> sent(@RequestParam(required = false, defaultValue = "ALL") String status,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false, defaultValue = "1") int page,
                                    @RequestParam(required = false, defaultValue = "10") int size,
                                    Authentication authentication) {
        String userId = CurrentUser.require(authentication);
        return ccService.listSent(userId, status, keyword, page, size);
    }

    /**
     * 我发出的传阅 · 按流程聚合：一次看清某个流程传给了谁、谁还没读。
     *
     * <p>与 {@code /sent} 同一批数据，发送人同样只取认证上下文。</p>
     */
    @GetMapping("/sent/grouped")
    public Map<String, Object> sentGrouped(@RequestParam(required = false, defaultValue = "ALL") String status,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false, defaultValue = "1") int page,
                                           @RequestParam(required = false, defaultValue = "10") int size,
                                           Authentication authentication) {
        String userId = CurrentUser.require(authentication);
        return ccService.listSentGroupedByProcess(userId, status, keyword, page, size);
    }

    /** 按流程批量撤回：仅撤回本人发出、对方未读、尚未撤回的记录 */
    @PostMapping("/process/{processInstanceId}/revoke")
    public Map<String, Object> revokeByProcess(@PathVariable String processInstanceId,
                                               Authentication authentication) {
        String userId = CurrentUser.require(authentication);
        return ccService.revokeBatchByProcess(processInstanceId, userId);
    }

    /** 未读传阅数（菜单角标） */
    @GetMapping("/unread-count")
    public Map<String, Object> unreadCount(Authentication authentication) {
        String userId = CurrentUser.require(authentication);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", ccService.countUnread(userId));
        return result;
    }

    /** 某流程实例的传阅记录：可见范围与流程跟踪一致（发起人、办理人、待阅人） */
    @GetMapping("/process/{processInstanceId}")
    public List<Map<String, Object>> processRecords(@PathVariable String processInstanceId,
                                                    Authentication authentication) {
        String userId = CurrentUser.require(authentication);
        if (!processTraceService.canView(processInstanceId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看该流程的传阅记录");
        }
        return ccService.listByProcess(processInstanceId, userId);
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
}
