package com.example.camundademo.controller;

import com.example.camundademo.security.AdminAccess;
import com.example.camundademo.security.CurrentUser;
import com.example.camundademo.service.CcHandleReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 传阅 / 承办统计报表接口。
 *
 * <p>报表包含“谁传阅给谁、谁逾期未办”，属于管理视角的全局数据，
 * 因此只对 {@code camunda-admin} 开放。前端菜单隐藏只是体验优化，
 * 权限在这里独立校验——把 URL 直接敲进浏览器同样会被拒绝。</p>
 *
 * <p>本接口只读，不会标记已读、不会推进流程，也不会修改任何承办状态。</p>
 */
@RestController
@RequestMapping("/api/report")
public class ReportController {

    private static final int MAX_DAYS = 3650;

    @Autowired
    private CcHandleReportService reportService;

    @Autowired
    private AdminAccess adminAccess;

    /**
     * @param days 统计区间天数；0 表示全部历史
     * @param top  排行榜条数，默认 20
     */
    @GetMapping("/cc-handle")
    public Map<String, Object> ccHandle(@RequestParam(required = false, defaultValue = "30") int days,
                                        @RequestParam(required = false, defaultValue = "20") int top,
                                        Authentication authentication) {
        String userId = CurrentUser.require(authentication);
        if (!adminAccess.isAdmin(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "仅 " + AdminAccess.ADMIN_GROUP_ID + " 组成员可以查看传阅与承办统计");
        }
        return reportService.overview(Math.min(Math.max(days, 0), MAX_DAYS), top);
    }
}
