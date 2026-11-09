package com.example.camundademo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 阅办逾期扫描。
 *
 * <p>每 60 秒把已过办理时限、仍未完成的阅办标记为 OVERDUE，
 * 用户不需要打开页面才会看到“逾期”。查询接口另外还会按 deadline 实时推算，
 * 因此即使定时任务短暂停摆，列表状态也不会算错。</p>
 */
@Component
public class HandleDeadlineScheduler {

    private static final Logger log = LoggerFactory.getLogger(HandleDeadlineScheduler.class);

    @Autowired
    private HandleService handleService;

    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void markOverdue() {
        try {
            int updated = handleService.markOverdue();
            if (updated > 0) {
                log.info("阅办逾期扫描：本次标记 {} 条", updated);
            }
        } catch (RuntimeException e) {
            // 定时任务异常不能中断后续调度
            log.error("阅办逾期扫描失败", e);
        }
    }
}
