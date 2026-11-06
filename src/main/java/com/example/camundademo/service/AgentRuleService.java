package com.example.camundademo.service;

import com.example.camundademo.entity.BizAgentRule;
import com.example.camundademo.entity.BizTaskDelegate;
import com.example.camundademo.entity.BizTaskExt;
import com.example.camundademo.persistence.BizAgentRuleManager;
import com.example.camundademo.persistence.BizTaskDelegateManager;
import com.example.camundademo.persistence.BizTaskExtManager;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.task.DelegationState;
import org.camunda.bpm.engine.task.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 代理规则服务。
 *
 * <p>规则表达的是“我在某段时间内，把某个范围内新产生的待办自动委托给代理人”。
 * 真正的触发点在 {@code GlobalAgentTaskListener}（任务 create 事件），
 * 本类只负责规则本身的增删改查与合法性校验。</p>
 *
 * <p><b>为什么必须校验时间段不重叠</b>：同一时刻存在两条生效规则时，
 * 监听器只能按创建时间取一条，另一条永远不生效但不报错——这是最难排查的一类配置错误。
 * 因此重叠直接拒掉，而不是在运行时“谁最后创建谁生效”。</p>
 */
@Service
public class AgentRuleService {

    /** 视为管理员的内置组名，命中任意一个即可强制取消他人规则 */
    private static final String[] ADMIN_GROUPS = {"admin", "camunda-admin"};

    private static final int MAX_KEYS_LENGTH = 1000;

    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private IdentityService identityService;

    @Autowired
    private TaskService taskService;

    /** 停用规则时自动收回委托留下的说明，与人工收回区分开 */
    public static final String DISABLE_CANCEL_REASON = "代理规则已停用，自动收回";

    private ProcessEngineConfigurationImpl getEngineConfig() {
        return (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
    }

    // ===== 写 =====

    /**
     * 新建或修改代理规则。修改时只允许本人操作，管理员也不能改别人的规则（只能停用）。
     */
    public Map<String, Object> save(String id, String operator, String agentUserId,
                                    Date startTime, Date endTime, String processDefinitionKeys) {
        // 不传办理方式 = 保持原值（新建时默认 CONFIRM），避免老前端编辑规则时把 SIGN 规则悄悄降级
        return save(id, operator, agentUserId, startTime, endTime, processDefinitionKeys, null);
    }

    /**
     * 新建或修改代理规则，可指定办理方式。
     *
     * @param delegateMode {@code CONFIRM} 受托人只能提交反馈；{@code SIGN} 受托人可直接办结；
     *                     传 null 表示修改时保持原值、新建时取默认 {@code CONFIRM}
     */
    public Map<String, Object> save(String id, String operator, String agentUserId,
                                    Date startTime, Date endTime, String processDefinitionKeys,
                                    String delegateMode) {
        if (startTime == null || endTime == null) {
            throw new BusinessException("请填写代理开始与结束时间");
        }
        if (!startTime.before(endTime)) {
            throw new BusinessException("代理开始时间必须早于结束时间");
        }
        String agent = agentUserId == null ? null : agentUserId.trim();
        if (agent == null || agent.isEmpty()) {
            throw new BusinessException("请选择代理人");
        }
        if (agent.equals(operator)) {
            throw new BusinessException("不能代理给自己");
        }
        if (identityService.createUserQuery().userId(agent).count() == 0) {
            throw new BusinessException("代理人不存在：" + agent);
        }
        String keys = normalizeKeys(processDefinitionKeys);
        String mode = normalizeMode(delegateMode);

        return getEngineConfig().getCommandExecutorTxRequired().execute((Command<Map<String, Object>>)
                commandContext -> {
                    BizAgentRuleManager ruleMgr = commandContext.getSession(BizAgentRuleManager.class);
                    Date now = new Date();
                    BizAgentRule rule;
                    if (id == null || id.isBlank()) {
                        rule = new BizAgentRule();
                        rule.setId(UUID.randomUUID().toString().replace("-", ""));
                        rule.setOwnerUserId(operator);
                        rule.setEnabled(1);
                        rule.setDelegateMode(mode == null ? BizAgentRule.MODE_CONFIRM : mode);
                        rule.setCreateTime(now);
                    } else {
                        rule = ruleMgr.findById(id);
                        if (rule == null) {
                            throw new BusinessException("代理规则不存在");
                        }
                        if (!operator.equals(rule.getOwnerUserId())) {
                            throw new BusinessException("只能修改自己的代理规则");
                        }
                        if (mode != null) {
                            rule.setDelegateMode(mode);
                        }
                    }

                    // 只对启用状态互查重叠：停用的规则不参与生效判定
                    for (BizAgentRule other : ruleMgr.findByOwner(operator)) {
                        if (other.getId().equals(rule.getId())) {
                            continue;
                        }
                        if (!other.isActive()) {
                            continue;
                        }
                        if (startTime.before(other.getEndTime()) && endTime.after(other.getStartTime())) {
                            throw new BusinessException("与已有代理规则时间重叠（"
                                    + format(other.getStartTime()) + " ~ " + format(other.getEndTime())
                                    + "），同一时间段只能有一条生效规则");
                        }
                    }

                    rule.setAgentUserId(agent);
                    rule.setStartTime(startTime);
                    rule.setEndTime(endTime);
                    rule.setProcessDefinitionKeys(keys);
                    rule.setUpdateTime(now);

                    if (id == null || id.isBlank()) {
                        ruleMgr.insert(rule);
                    } else {
                        ruleMgr.update(rule);
                    }

                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("result", "ok");
                    resp.put("rule", toVO(rule, now));
                    return resp;
                });
    }

    /**
     * 停用规则（默认不动已代理出去的任务）。
     *
     * <p>本人可停用；管理员可强制停用他人规则，但不能修改内容——
     * 这条约束让“管理员介入”只改变生效开关，不改变责任归属，便于事后审计。</p>
     */
    public Map<String, Object> disable(String id, String operator) {
        return disable(id, operator, false);
    }

    /**
     * 停用规则，可选“同时收回该规则下委托中的任务”。
     *
     * <p><b>为什么收回要显式勾选</b>：默认停用只影响新任务，已经在代理人手里的任务保持原样，
     * 避免代理人处理到一半时任务突然消失。但如果这批任务本来就是为了“临时离开”而委托出去的，
     * 停用后落在别人名下反而没人管，因此提供一个可选的批量收回。</p>
     *
     * <p>收回顺序与 {@link TaskDelegateService#cancel} 保持一致：先 {@code resolveTask}，
     * 再改业务记录状态。assignment 回调会先命中代理监听器的“已有 PENDING 记录”幂等判断，
     * 不会在收回的同一秒又被规则重新代理出去。</p>
     *
     * @param cancelActive 是否同时收回该规则下仍在委托中的任务
     */
    public Map<String, Object> disable(String id, String operator, boolean cancelActive) {
        return setEnabled(id, operator, 0, cancelActive);
    }

    public Map<String, Object> enable(String id, String operator) {
        return setEnabled(id, operator, 1, false);
    }

    private Map<String, Object> setEnabled(String id, String operator, int enabled) {
        return setEnabled(id, operator, enabled, false);
    }

    private Map<String, Object> setEnabled(String id, String operator, int enabled, boolean cancelActive) {
        return getEngineConfig().getCommandExecutorTxRequired().execute((Command<Map<String, Object>>)
                commandContext -> {
                    BizAgentRuleManager ruleMgr = commandContext.getSession(BizAgentRuleManager.class);
                    BizAgentRule rule = ruleMgr.findById(id);
                    if (rule == null) {
                        throw new BusinessException("代理规则不存在");
                    }
                    boolean isOwner = operator.equals(rule.getOwnerUserId());
                    if (!isOwner && !isAdmin(operator)) {
                        throw new BusinessException("只有规则本人或管理员可以变更生效状态");
                    }
                    if (enabled == 1 && !isOwner) {
                        // 管理员恢复规则等于替人授权，属于修改内容，不允许
                        throw new BusinessException("管理员只能停用规则，不能替他人启用");
                    }
                    Date now = new Date();
                    rule.setEnabled(enabled);
                    rule.setUpdateTime(now);
                    ruleMgr.update(rule);

                    int canceled = 0;
                    int skipped = 0;
                    if (enabled == 0 && cancelActive) {
                        CancelResult result = cancelPendingOfRule(commandContext, rule);
                        canceled = result.canceled;
                        skipped = result.skipped;
                    }

                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("result", "ok");
                    resp.put("rule", toVO(rule, now, 0));
                    resp.put("canceled", canceled);
                    resp.put("skipped", skipped);
                    return resp;
                });
    }

    /** 停止一条规则下仍在委托中的任务，返回成功收回与跳过（任务已结束/已不在委托中）的条数 */
    private CancelResult cancelPendingOfRule(CommandContext commandContext, BizAgentRule rule) {
        BizTaskDelegateManager delegateMgr = commandContext.getSession(BizTaskDelegateManager.class);
        BizTaskExtManager extMgr = commandContext.getSession(BizTaskExtManager.class);
        CancelResult result = new CancelResult();
        for (BizTaskDelegate record : delegateMgr.findPendingByAgentRuleIds(List.of(rule.getId()))) {
            Task task = taskService.createTaskQuery().taskId(record.getTaskId()).singleResult();
            // CONFIRM：引擎处于委托中（PENDING），收回走 resolveTask
            boolean confirmHeld = task != null && DelegationState.PENDING.equals(task.getDelegationState());
            // SIGN：引擎没有委托状态，任务直接挂在代理人名下，收回只能改派回委托人。
            // 必须比对 record 里的代理人，否则规则改过代理人后会把别人的任务抢回来。
            boolean signHeld = task != null
                    && BizTaskDelegate.MODE_SIGN.equals(record.getDelegateMode())
                    && task.getDelegationState() == null
                    && task.getAssignee() != null
                    && task.getAssignee().equals(record.getDelegateUserId());
            // 任务已被办结、已被别人改派、或已不在这条规则的负责人名下：只跳过，不改记录，
            // 避免把“事实上的其他状态”写成自动收回
            boolean cancellable = task != null
                    && rule.getOwnerUserId().equals(task.getOwner())
                    && (confirmHeld || signHeld);
            if (!cancellable) {
                result.skipped++;
                continue;
            }
            if (signHeld) {
                // 先改派、后改记录：assignment 回调会命中代理监听器的“已有 PENDING 记录”幂等判断
                taskService.setAssignee(task.getId(), rule.getOwnerUserId());
            } else {
                taskService.resolveTask(task.getId());
            }

            Date now = new Date();
            record.setStatus(BizTaskDelegate.STATUS_CANCELED);
            record.setResolveContent(DISABLE_CANCEL_REASON);
            record.setResolveTime(now);
            record.setUpdateTime(now);
            delegateMgr.update(record);

            BizTaskExt ext = extMgr.findByTaskId(task.getId());
            if (ext != null) {
                ext.setAssignee(rule.getOwnerUserId());
                ext.setUpdateTime(now);
                extMgr.update(ext);
            }
            result.canceled++;
        }
        return result;
    }

    private static final class CancelResult {
        private int canceled;
        private int skipped;
    }

    // ===== 读 =====

    public List<Map<String, Object>> listMine(String ownerUserId) {
        return getEngineConfig().getCommandExecutorTxRequired().execute((Command<List<Map<String, Object>>>)
                commandContext -> {
                    Date now = new Date();
                    List<BizAgentRule> rules = commandContext.getSession(BizAgentRuleManager.class)
                            .findByOwner(ownerUserId);
                    // 一次查完所有规则的委托中条数，避免按规则逐条查（N+1）
                    Map<String, Integer> pending = countPendingByRule(commandContext, rules);
                    List<Map<String, Object>> result = new ArrayList<>();
                    for (BizAgentRule rule : rules) {
                        result.add(toVO(rule, now, pending.getOrDefault(rule.getId(), 0)));
                    }
                    return result;
                });
    }

    /**
     * 管理员视图：全部规则。
     *
     * <p>强制停用需要先拿到规则 ID，而本人列表只返回自己的规则，
     * 没有这个入口时管理员只能靠数据库查 ID，等于“能力存在但不可用”。</p>
     */
    public List<Map<String, Object>> listAll(String operator) {
        if (!isAdmin(operator)) {
            throw new BusinessException("仅管理员可查看全部代理规则");
        }
        return getEngineConfig().getCommandExecutorTxRequired().execute((Command<List<Map<String, Object>>>)
                commandContext -> {
                    Date now = new Date();
                    List<BizAgentRule> rules = commandContext.getSession(BizAgentRuleManager.class).findAll();
                    Map<String, Integer> pending = countPendingByRule(commandContext, rules);
                    List<Map<String, Object>> result = new ArrayList<>();
                    for (BizAgentRule rule : rules) {
                        result.add(toVO(rule, now, pending.getOrDefault(rule.getId(), 0)));
                    }
                    return result;
                });
    }

    // ===== 内部 =====

    public boolean isAdmin(String userId) {
        for (String group : ADMIN_GROUPS) {
            if (identityService.createGroupQuery().groupMember(userId).groupId(group).count() > 0) {
                return true;
            }
        }
        return false;
    }

    /** 办理方式归一化：空 = 未指定（保持原值）；非法值直接拒绝，避免落库后监听器行为不明 */
    private String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return null;
        }
        String value = mode.trim().toUpperCase();
        if (!BizAgentRule.MODE_CONFIRM.equals(value) && !BizAgentRule.MODE_SIGN.equals(value)) {
            throw new BusinessException("办理方式只能是 CONFIRM（需本人确认）或 SIGN（代理人可直接办结）");
        }
        return value;
    }

    private String normalizeKeys(String keys) {        if (keys == null || keys.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String key : keys.split(",")) {
            String trimmed = key.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(trimmed);
        }
        if (sb.length() == 0) {
            return null;
        }
        return sb.length() > MAX_KEYS_LENGTH ? sb.substring(0, MAX_KEYS_LENGTH) : sb.toString();
    }

    private String format(Date date) {
        return date == null ? "-" : new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(date);
    }

    /**
     * 该规则下“仍在委托中”的条数：业务记录 PENDING 且任务仍在运行。
     *
     * <p>只看记录状态不够——任务办结后记录不会删（留痕），一条早已结束的委托会一直算作 PENDING。
     * 这里按 200 个一批查运行时任务（Oracle 的 IN 上限是 1000），只统计任务还活着的记录。</p>
     */
    private Map<String, Integer> countPendingByRule(CommandContext commandContext, List<BizAgentRule> rules) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (rules == null || rules.isEmpty()) {
            return counts;
        }
        List<String> ruleIds = new ArrayList<>();
        for (BizAgentRule rule : rules) {
            ruleIds.add(rule.getId());
        }
        List<BizTaskDelegate> pendings = commandContext.getSession(BizTaskDelegateManager.class)
                .findPendingByAgentRuleIds(ruleIds);
        if (pendings.isEmpty()) {
            return counts;
        }
        Set<String> taskIds = new LinkedHashSet<>();
        for (BizTaskDelegate record : pendings) {
            if (record.getTaskId() != null) {
                taskIds.add(record.getTaskId());
            }
        }
        Set<String> activeTaskIds = new HashSet<>();
        List<String> ids = new ArrayList<>(taskIds);
        for (int i = 0; i < ids.size(); i += 200) {
            List<String> chunk = ids.subList(i, Math.min(i + 200, ids.size()));
            for (Task task : taskService.createTaskQuery().taskIdIn(chunk.toArray(new String[0])).list()) {
                activeTaskIds.add(task.getId());
            }
        }
        for (BizTaskDelegate record : pendings) {
            if (record.getTaskId() != null && activeTaskIds.contains(record.getTaskId())) {
                counts.merge(record.getAgentRuleId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    public Map<String, Object> toVO(BizAgentRule rule, Date now) {
        return toVO(rule, now, 0);
    }

    public Map<String, Object> toVO(BizAgentRule rule, Date now, int pendingCount) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", rule.getId());
        vo.put("owner", rule.getOwnerUserId());
        vo.put("agentUser", rule.getAgentUserId());
        vo.put("startTime", rule.getStartTime());
        vo.put("endTime", rule.getEndTime());
        vo.put("processDefinitionKeys", rule.getProcessDefinitionKeys());
        vo.put("enabled", rule.getEnabled());
        vo.put("delegateMode", rule.getDelegateMode() == null ? BizAgentRule.MODE_CONFIRM : rule.getDelegateMode());
        vo.put("state", stateOf(rule, now));
        // 停用弹窗要据此显示“同时收回 N 条委托中的任务”
        vo.put("pendingCount", pendingCount);
        vo.put("createTime", rule.getCreateTime());
        vo.put("updateTime", rule.getUpdateTime());
        return vo;
    }

    /** 规则对外状态：停用 / 未开始 / 生效中 / 已过期 */
    private String stateOf(BizAgentRule rule, Date now) {
        if (!rule.isActive()) {
            return "DISABLED";
        }
        if (rule.getStartTime() != null && now.before(rule.getStartTime())) {
            return "UPCOMING";
        }
        if (rule.getEndTime() != null && now.after(rule.getEndTime())) {
            return "EXPIRED";
        }
        return "ACTIVE";
    }
}
