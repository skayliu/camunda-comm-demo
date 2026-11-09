package com.example.camundademo.service;

import com.example.camundademo.entity.BizCcRecord;
import com.example.camundademo.entity.BizHandleRecord;
import com.example.camundademo.persistence.BizCcRecordManager;
import com.example.camundademo.persistence.BizHandleRecordManager;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.identity.User;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 传阅 / 阅办统计报表服务。
 *
 * <p>报表回答两个管理问题：<b>谁传给了谁</b>（送达是否到位、对方看没看）、
 * <b>谁逾期没办</b>（承办是否守时）。</p>
 *
 * <p>取数只按时间切片，统计全部在 Java 里完成。原因是这些表要在 H2 / MySQL /
 * PostgreSQL 等多个数据库上跑，SQL 层的日期截断、空值排序、分组行为各不相同，
 * 同一份报表在开发库和线上库会给出不同数字；把口径收在服务层就只有一套实现。</p>
 *
 * <p>本服务只读，不修改任何业务状态，也不会触碰引擎任务。</p>
 */
@Service
public class CcHandleReportService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int NAME_BATCH = 200;
    private static final int DEFAULT_TOP = 20;
    private static final int MAX_OVERDUE_ITEMS = 50;

    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private IdentityService identityService;

    /**
     * 汇总报表。
     *
     * @param days 统计区间天数，&lt;= 0 表示全部历史
     * @param top  排行榜取前多少名
     */
    public Map<String, Object> overview(int days, int top) {
        int safeDays = Math.max(days, 0);
        int safeTop = Math.min(Math.max(top, 1), 100);
        Date since = safeDays > 0
                ? Date.from(LocalDate.now().minusDays(safeDays - 1L)
                        .atStartOfDay(ZoneId.systemDefault()).toInstant())
                : null;

        List<BizCcRecord> ccs = tx(context ->
                context.getSession(BizCcRecordManager.class).findForReport(since));
        List<BizHandleRecord> handles = tx(context ->
                context.getSession(BizHandleRecordManager.class).findForReport(since));

        Map<String, String> names = resolveNames(ccs, handles);
        Date now = new Date();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("range", range(safeDays, since));
        result.put("summary", summary(ccs, handles, now));
        result.put("cc", ccSection(ccs, names, safeTop));
        result.put("handle", handleSection(handles, names, now));
        result.put("trend", trend(ccs, handles, safeDays));
        return result;
    }

    // ===== 传阅 =====

    private Map<String, Object> ccSection(List<BizCcRecord> records, Map<String, String> names, int top) {
        Map<String, PairStat> pairs = new LinkedHashMap<>();
        Map<String, UserStat> senders = new LinkedHashMap<>();
        Map<String, UserStat> receivers = new LinkedHashMap<>();
        Map<String, Long> types = new LinkedHashMap<>();
        long active = 0;
        long read = 0;
        long unread = 0;
        long revoked = 0;

        for (BizCcRecord record : records) {
            boolean isRevoked = isRevoked(record);
            boolean isRead = !isRevoked && isRead(record);
            if (isRevoked) {
                revoked++;
            } else {
                active++;
                if (isRead) {
                    read++;
                } else {
                    unread++;
                }
            }
            types.merge(ccTypeName(record.getCcType()), 1L, Long::sum);

            String senderId = nullToDash(record.getSenderUserId());
            String receiverId = nullToDash(record.getCcUserId());
            pairs.computeIfAbsent(senderId + "\u0001" + receiverId,
                            key -> new PairStat(senderId, receiverId))
                    .add(isRead, isRevoked);
            senders.computeIfAbsent(senderId, key -> new UserStat(senderId))
                    .add(isRead, isRevoked);
            receivers.computeIfAbsent(receiverId, key -> new UserStat(receiverId))
                    .add(isRead, isRevoked);
        }

        List<Map<String, Object>> pairRows = new ArrayList<>();
        for (PairStat stat : sortPairs(pairs.values(), top)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sender", stat.senderId);
            row.put("senderName", name(names, stat.senderId));
            row.put("receiver", stat.receiverId);
            row.put("receiverName", name(names, stat.receiverId));
            row.put("total", stat.total);
            row.put("read", stat.read);
            row.put("unread", stat.unread);
            row.put("revoked", stat.revoked);
            row.put("readRate", rate(stat.read, stat.read + stat.unread));
            pairRows.add(row);
        }

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("active", active);
        section.put("read", read);
        section.put("unread", unread);
        section.put("revoked", revoked);
        section.put("readRate", rate(read, active));
        section.put("pairs", pairRows);
        section.put("senders", userRows(senders.values(), names, top));
        section.put("receivers", userRows(receivers.values(), names, top));
        section.put("types", typeRows(types));
        return section;
    }

    // ===== 阅办 =====

    private Map<String, Object> handleSection(List<BizHandleRecord> records,
                                              Map<String, String> names, Date now) {
        Map<String, HandleStat> assignees = new LinkedHashMap<>();
        long total = 0;
        long done = 0;
        long open = 0;
        long overdue = 0;
        long revoked = 0;
        Map<String, Long> statuses = new LinkedHashMap<>();
        List<Map<String, Object>> overdueItems = new ArrayList<>();

        for (BizHandleRecord record : records) {
            boolean isRevoked = isRevoked(record);
            boolean isDone = BizHandleRecord.STATUS_DONE.equals(record.getHandleStatus());
            boolean isOverdue = !isRevoked && !isDone && isOverdue(record, now);

            if (isRevoked) {
                revoked++;
            } else {
                total++;
                if (isDone) {
                    done++;
                } else {
                    open++;
                    if (isOverdue) {
                        overdue++;
                    }
                }
            }
            statuses.merge(statusText(record.getHandleStatus(), isRevoked), 1L, Long::sum);

            String assignee = nullToDash(record.getAssigneeId());
            assignees.computeIfAbsent(assignee, key -> new HandleStat(assignee))
                    .add(isDone, isOverdue, isRevoked, hours(record));

            if (isOverdue) {
                overdueItems.add(overdueRow(record, names, now));
            }
        }

        // 逾期最久的排最前面：管理上先看拖得最狠的那几件
        overdueItems.sort(Comparator.comparingDouble(
                (Map<String, Object> row) -> ((Number) row.get("overdueHours")).doubleValue()).reversed());
        if (overdueItems.size() > MAX_OVERDUE_ITEMS) {
            overdueItems = new ArrayList<>(overdueItems.subList(0, MAX_OVERDUE_ITEMS));
        }

        List<Map<String, Object>> assigneeRows = new ArrayList<>();
        List<HandleStat> sorted = new ArrayList<>(assignees.values());
        sorted.sort(Comparator
                .comparingLong((HandleStat stat) -> stat.overdue).reversed()
                .thenComparing(Comparator.comparingLong((HandleStat stat) -> stat.total).reversed())
                .thenComparing(stat -> stat.assigneeId));
        for (HandleStat stat : sorted) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("assignee", stat.assigneeId);
            row.put("assigneeName", name(names, stat.assigneeId));
            row.put("total", stat.total);
            row.put("done", stat.done);
            row.put("open", stat.open);
            row.put("overdue", stat.overdue);
            row.put("revoked", stat.revoked);
            row.put("doneRate", rate(stat.done, stat.total));
            row.put("overdueRate", rate(stat.overdue, stat.total));
            row.put("avgHours", stat.doneWithTime > 0
                    ? round1(stat.hoursSum / stat.doneWithTime) : null);
            assigneeRows.add(row);
        }

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("total", total);
        section.put("done", done);
        section.put("open", open);
        section.put("overdue", overdue);
        section.put("revoked", revoked);
        section.put("doneRate", rate(done, total));
        section.put("overdueRate", rate(overdue, total));
        section.put("avgHours", done > 0 ? avgHours(records) : null);
        section.put("assignees", assigneeRows);
        section.put("overdueItems", overdueItems);
        section.put("statuses", statusRows(statuses));
        return section;
    }

    private Map<String, Object> overdueRow(BizHandleRecord record,
                                           Map<String, String> names, Date now) {
        long overdueMillis = Math.max(now.getTime() - record.getDeadline().getTime(), 0L);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", record.getId());
        row.put("title", record.getTitle());
        row.put("processInstanceId", record.getProcessInstanceId());
        row.put("processDefinitionKey", record.getProcessDefinitionKey());
        row.put("assignee", record.getAssigneeId());
        row.put("assigneeName", name(names, record.getAssigneeId()));
        row.put("sender", record.getSenderId());
        row.put("senderName", name(names, record.getSenderId()));
        row.put("instruction", record.getInstruction());
        row.put("deadline", record.getDeadline());
        row.put("createTime", record.getCreateTime());
        row.put("handleStatus", record.getHandleStatus());
        row.put("statusText", statusText(record.getHandleStatus(), false));
        row.put("overdueHours", round1(overdueMillis / 3_600_000d));
        row.put("overdueDays", (int) Math.ceil(overdueMillis / 86_400_000d));
        return row;
    }

    private Double avgHours(List<BizHandleRecord> records) {
        double sum = 0;
        int count = 0;
        for (BizHandleRecord record : records) {
            Double value = hours(record);
            if (value != null && BizHandleRecord.STATUS_DONE.equals(record.getHandleStatus())) {
                sum += value;
                count++;
            }
        }
        return count == 0 ? null : round1(sum / count);
    }

    /** 从发起到反馈结果的小时数，任一时间为空则不计入平均 */
    private Double hours(BizHandleRecord record) {
        if (record.getCreateTime() == null || record.getHandleTime() == null) {
            return null;
        }
        return (record.getHandleTime().getTime() - record.getCreateTime().getTime()) / 3_600_000d;
    }

    // ===== 趋势 =====

    private List<Map<String, Object>> trend(List<BizCcRecord> ccs,
                                            List<BizHandleRecord> handles, int days) {
        Map<LocalDate, long[]> byDay = new TreeMap<>();
        for (BizCcRecord record : ccs) {
            LocalDate day = toDay(record.getCreateTime());
            if (day != null) {
                byDay.computeIfAbsent(day, key -> new long[3])[0]++;
            }
        }
        for (BizHandleRecord record : handles) {
            LocalDate created = toDay(record.getCreateTime());
            if (created != null) {
                byDay.computeIfAbsent(created, key -> new long[3])[1]++;
            }
            if (BizHandleRecord.STATUS_DONE.equals(record.getHandleStatus())) {
                LocalDate finished = toDay(record.getHandleTime());
                if (finished != null) {
                    byDay.computeIfAbsent(finished, key -> new long[3])[2]++;
                }
            }
        }

        // 指定区间要补齐空白日期，否则折线会被压缩成“每天都有数据”的错觉
        if (days > 0) {
            LocalDate today = LocalDate.now();
            for (int i = days - 1; i >= 0; i--) {
                byDay.computeIfAbsent(today.minusDays(i), key -> new long[3]);
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        int index = 0;
        int size = byDay.size();
        for (Map.Entry<LocalDate, long[]> entry : byDay.entrySet()) {
            // 全部历史模式下只保留最近 90 天，避免一次返回数百个点
            if (days <= 0 && size > 90 && index < size - 90) {
                index++;
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", entry.getKey().format(DAY));
            row.put("cc", entry.getValue()[0]);
            row.put("handle", entry.getValue()[1]);
            row.put("done", entry.getValue()[2]);
            rows.add(row);
            index++;
        }
        return rows;
    }

    // ===== 汇总 =====

    private Map<String, Object> summary(List<BizCcRecord> ccs, List<BizHandleRecord> handles, Date now) {
        long ccActive = 0;
        long ccRead = 0;
        long ccRevoked = 0;
        for (BizCcRecord record : ccs) {
            if (isRevoked(record)) {
                ccRevoked++;
            } else {
                ccActive++;
                if (isRead(record)) {
                    ccRead++;
                }
            }
        }

        long handleTotal = 0;
        long handleDone = 0;
        long handleOverdue = 0;
        long handleRevoked = 0;
        for (BizHandleRecord record : handles) {
            boolean isRevoked = isRevoked(record);
            boolean isDone = BizHandleRecord.STATUS_DONE.equals(record.getHandleStatus());
            if (isRevoked) {
                handleRevoked++;
                continue;
            }
            handleTotal++;
            if (isDone) {
                handleDone++;
            } else if (isOverdue(record, now)) {
                handleOverdue++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("ccTotal", ccs.size());
        summary.put("ccActive", ccActive);
        summary.put("ccRead", ccRead);
        summary.put("ccUnread", ccActive - ccRead);
        summary.put("ccRevoked", ccRevoked);
        summary.put("ccReadRate", rate(ccRead, ccActive));
        summary.put("handleTotal", handleTotal);
        summary.put("handleDone", handleDone);
        summary.put("handleOpen", handleTotal - handleDone);
        summary.put("handleOverdue", handleOverdue);
        summary.put("handleRevoked", handleRevoked);
        summary.put("handleDoneRate", rate(handleDone, handleTotal));
        summary.put("handleOverdueRate", rate(handleOverdue, handleTotal));
        summary.put("handleAvgHours", handleTotal > 0 ? avgHours(handles) : null);
        return summary;
    }

    private Map<String, Object> range(int days, Date since) {
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("days", days);
        range.put("since", since);
        range.put("until", new Date());
        range.put("label", days > 0 ? "近 " + days + " 天" : "全部历史");
        return range;
    }

    // ===== 名称 =====

    /** 一次性解析报表里出现的所有账号，避免逐行查身份库（N+1） */
    private Map<String, String> resolveNames(List<BizCcRecord> ccs, List<BizHandleRecord> handles) {
        Set<String> ids = new HashSet<>();
        for (BizCcRecord record : ccs) {
            addId(ids, record.getSenderUserId());
            addId(ids, record.getCcUserId());
        }
        for (BizHandleRecord record : handles) {
            addId(ids, record.getAssigneeId());
            addId(ids, record.getSenderId());
        }
        Map<String, String> names = new HashMap<>();
        List<String> list = new ArrayList<>(ids);
        for (int i = 0; i < list.size(); i += NAME_BATCH) {
            List<String> chunk = list.subList(i, Math.min(i + NAME_BATCH, list.size()));
            try {
                for (User user : identityService.createUserQuery()
                        .userIdIn(chunk.toArray(new String[0])).list()) {
                    String display = user.getFirstName();
                    names.put(user.getId(),
                            display == null || display.isBlank() ? user.getId() : display);
                }
            } catch (RuntimeException ignored) {
                // 身份库不可用时退回账号显示，报表本身不应因此失败
            }
        }
        return names;
    }

    private void addId(Set<String> ids, String id) {
        if (id != null && !id.isBlank()) {
            ids.add(id);
        }
    }

    private String name(Map<String, String> names, String userId) {
        if (userId == null) {
            return "-";
        }
        return names.getOrDefault(userId, userId);
    }

    // ===== 行数据 =====

    private List<Map<String, Object>> userRows(Iterable<UserStat> stats,
                                               Map<String, String> names, int top) {
        List<UserStat> list = new ArrayList<>();
        stats.forEach(list::add);
        list.sort(Comparator
                .comparingLong((UserStat stat) -> stat.unread + stat.revoked).reversed()
                .thenComparing(Comparator.comparingLong((UserStat stat) -> stat.total).reversed()));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (UserStat stat : list) {
            if (rows.size() >= top) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", stat.userId);
            row.put("userName", name(names, stat.userId));
            row.put("total", stat.total);
            row.put("read", stat.read);
            row.put("unread", stat.unread);
            row.put("revoked", stat.revoked);
            row.put("readRate", rate(stat.read, stat.read + stat.unread));
            rows.add(row);
        }
        return rows;
    }

    private List<PairStat> sortPairs(Iterable<PairStat> stats, int top) {
        List<PairStat> list = new ArrayList<>();
        stats.forEach(list::add);
        list.sort(Comparator
                .comparingLong((PairStat stat) -> stat.total).reversed()
                .thenComparing(Comparator.comparingLong((PairStat stat) -> stat.unread).reversed())
                .thenComparing(stat -> stat.senderId)
                .thenComparing(stat -> stat.receiverId));
        return list.size() > top ? new ArrayList<>(list.subList(0, top)) : list;
    }

    private List<Map<String, Object>> typeRows(Map<String, Long> types) {
        List<Map<String, Object>> rows = new ArrayList<>();
        types.forEach((key, value) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", key);
            row.put("value", value);
            rows.add(row);
        });
        rows.sort(Comparator.comparingLong(
                (Map<String, Object> row) -> -((Long) row.get("value"))));
        return rows;
    }

    private List<Map<String, Object>> statusRows(Map<String, Long> statuses) {
        List<Map<String, Object>> rows = new ArrayList<>();
        statuses.forEach((key, value) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", key);
            row.put("value", value);
            rows.add(row);
        });
        rows.sort(Comparator.comparingLong(
                (Map<String, Object> row) -> -((Long) row.get("value"))));
        return rows;
    }

    // ===== 口径工具 =====

    private boolean isRead(BizCcRecord record) {
        return Objects.equals(record.getRead(), 1);
    }

    private boolean isRevoked(BizCcRecord record) {
        return Objects.equals(record.getRevoked(), 1);
    }

    private boolean isRevoked(BizHandleRecord record) {
        return Objects.equals(record.getRevoked(), 1);
    }

    /**
     * 逾期以 deadline 实时计算，不只看 HANDLE_STATUS_：
     * 定时任务每 60 秒才扫一次，报表若只认状态字段，最近一分钟内到期的会被漏算。
     */
    private boolean isOverdue(BizHandleRecord record, Date now) {
        if (BizHandleRecord.STATUS_DONE.equals(record.getHandleStatus())) {
            return false;
        }
        return record.getDeadline() != null && record.getDeadline().before(now);
    }

    private String ccTypeName(String type) {
        if (BizCcRecord.TYPE_INST.equals(type)) {
            return "流程传阅";
        }
        if (BizCcRecord.TYPE_MANUAL.equals(type)) {
            return "手动传阅";
        }
        return "节点传阅";
    }

    private String statusText(String status, boolean revoked) {
        if (revoked) {
            return "已撤回/失效";
        }
        if (BizHandleRecord.STATUS_DONE.equals(status)) {
            return "已完成";
        }
        if (BizHandleRecord.STATUS_HANDLING.equals(status)) {
            return "办理中";
        }
        if (BizHandleRecord.STATUS_OVERDUE.equals(status)) {
            return "逾期";
        }
        return "待阅";
    }

    private LocalDate toDay(Date date) {
        return date == null ? null
                : Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private double rate(long part, long total) {
        return total <= 0 ? 0d : round1(part * 100d / total);
    }

    private double round1(double value) {
        return Math.round(value * 10d) / 10d;
    }

    private <T> T tx(Command<T> command) {
        ProcessEngineConfigurationImpl configuration =
                (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
        return configuration.getCommandExecutorTxRequired().execute(command);
    }

    // ===== 统计载体 =====

    private static final class PairStat {
        private final String senderId;
        private final String receiverId;
        private long total;
        private long read;
        private long unread;
        private long revoked;

        private PairStat(String senderId, String receiverId) {
            this.senderId = senderId;
            this.receiverId = receiverId;
        }

        private void add(boolean isRead, boolean isRevoked) {
            total++;
            if (isRevoked) {
                revoked++;
            } else if (isRead) {
                read++;
            } else {
                unread++;
            }
        }
    }

    private static final class UserStat {
        private final String userId;
        private long total;
        private long read;
        private long unread;
        private long revoked;

        private UserStat(String userId) {
            this.userId = userId;
        }

        private void add(boolean isRead, boolean isRevoked) {
            total++;
            if (isRevoked) {
                revoked++;
            } else if (isRead) {
                read++;
            } else {
                unread++;
            }
        }
    }

    private static final class HandleStat {
        private final String assigneeId;
        private long total;
        private long done;
        private long open;
        private long overdue;
        private long revoked;
        private long doneWithTime;
        private double hoursSum;

        private HandleStat(String assigneeId) {
            this.assigneeId = assigneeId;
        }

        private void add(boolean isDone, boolean isOverdue, boolean isRevoked, Double hours) {
            if (isRevoked) {
                revoked++;
                return;
            }
            total++;
            if (isDone) {
                done++;
                if (hours != null) {
                    doneWithTime++;
                    hoursSum += hours;
                }
            } else {
                open++;
                if (isOverdue) {
                    overdue++;
                }
            }
        }
    }
}
