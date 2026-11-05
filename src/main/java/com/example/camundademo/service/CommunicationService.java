package com.example.camundademo.service;

import com.example.camundademo.entity.BizCommSession;
import com.example.camundademo.entity.BizCommTask;
import com.example.camundademo.entity.BizTaskExt;
import com.example.camundademo.persistence.BizCommSessionManager;
import com.example.camundademo.persistence.BizCommTaskManager;
import com.example.camundademo.persistence.BizTaskExtManager;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.impl.identity.Authentication;
import org.camunda.bpm.engine.impl.persistence.entity.HistoricProcessInstanceManager;
import org.camunda.bpm.engine.impl.persistence.entity.TaskEntity;
import org.camunda.bpm.engine.runtime.ProcessInstantiationBuilder;
import org.camunda.bpm.engine.task.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 旁路沟通核心服务
 */
@Service
public class CommunicationService {

    @Autowired
    private TaskService taskService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private ProcessEngine processEngine;

    private ProcessEngineConfigurationImpl getEngineConfig() {
        return (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
    }

    /**
     * 发起沟通（兼容旧签名：只返回会话ID）
     */
    public String startCommunication(String rootTaskId, String initiator, String content,
                                     List<Map<String, String>> targets) {
        Object sessionId = startCommunicationDetailed(rootTaskId, initiator, content, targets).get("sessionId");
        return sessionId == null ? null : sessionId.toString();
    }

    /**
     * 发起沟通，并返回本次操作的明细（是否追加、新增/跳过的人员）。
     *
     * <p><b>进行中会话上再次发起 = 补充沟通对象</b>：实际使用中经常出现“发完才发现漏了人”，
     * 老实现直接抛「当前待办已有进行中的沟通，请勿重复发起」，而前端又没有校验响应体里的
     * {@code error}，于是表现为“提示成功、后台报错、人员没加上”。现在改为在原会话内补齐：
     * 已在本次沟通中的对象跳过，只给新增对象建沟通任务，会话轮次与进行中状态保持不变。</p>
     */
    public Map<String, Object> startCommunicationDetailed(String rootTaskId, String initiator, String content,
                                                          List<Map<String, String>> targets) {
        ProcessEngineConfigurationImpl engineConfig = getEngineConfig();
        Map<String, Object> result = new LinkedHashMap<>();

        engineConfig.getCommandExecutorTxRequired().execute((Command<Void>) commandContext -> {
            Task rootTask = taskService.createTaskQuery().taskId(rootTaskId).singleResult();
            if (rootTask == null) {
                throw new BusinessException("源待办不存在");
            }
            // 身份校验：只有当前待办处理人才能发起沟通，避免任意用户代他人发起
            if (initiator == null || !initiator.equals(rootTask.getAssignee())) {
                throw new BusinessException("仅当前待办处理人可发起沟通");
            }
            // 归一化沟通对象：不同前端可能提交 id/name 或 name/value，
            // 这里统一解析出“账号标识 + 显示名”，避免 null 落库
            List<Map<String, String>> normalizedTargets = normalizeTargets(targets);

            BizTaskExtManager extMgr = commandContext.getSession(BizTaskExtManager.class);
            BizCommSessionManager sessionMgr = commandContext.getSession(BizCommSessionManager.class);
            BizCommTaskManager taskMgr = commandContext.getSession(BizCommTaskManager.class);

            BizTaskExt taskExt = extMgr.findByTaskId(rootTaskId);
            if (taskExt == null) {
                taskExt = new BizTaskExt();
                taskExt.setId(UUID.randomUUID().toString().replace("-", ""));
                taskExt.setTaskId(rootTaskId);
                taskExt.setProcessInstanceId(rootTask.getProcessInstanceId());
                taskExt.setAssignee(rootTask.getAssignee());
                taskExt.setBizStatus(BizTaskExt.STATUS_NORMAL);
                taskExt.setCreateTime(new Date());
                taskExt.setUpdateTime(new Date());
                extMgr.insert(taskExt);
            }

            // ① 已有进行中的会话：在原会话上补充沟通对象，而不是拒绝或另开一个新会话。
            //    这样“少加了人”的二次发起能真正生效，也不会把一次沟通拆成两个会话。
            BizCommSession activeSession = sessionMgr.findActiveByRootTask(rootTaskId);
            if (activeSession != null) {
                if (!initiator.equals(activeSession.getInitiator())) {
                    throw new BusinessException("本次沟通由 " + activeSession.getInitiator()
                            + " 发起，仅发起人可补充沟通对象");
                }
                String appendContent = firstNonBlank(content, activeSession.getInitialContent(), "");

                // 已在本次沟通中的对象：待反馈/已反馈都跳过；已收回的允许重新发起
                Set<String> joined = new HashSet<>();
                for (BizCommTask exist : taskMgr.findBySessionId(activeSession.getId())) {
                    if (BizCommTask.STATUS_WITHDRAWN.equals(exist.getStatus())) {
                        continue;
                    }
                    joined.add(targetKey(exist.getTargetType(), exist.getTargetId()));
                }

                List<String> added = new ArrayList<>();
                List<String> skipped = new ArrayList<>();
                for (Map<String, String> t : normalizedTargets) {
                    String key = targetKey(t.get("type"), t.get("id"));
                    if (joined.contains(key)) {
                        skipped.add(t.get("name"));
                        continue;
                    }
                    createCommTaskRecord(rootTask, activeSession.getId(), null, appendContent, t,
                            extMgr, taskMgr);
                    // 同一次提交里重复勾选同一人时也只建一条
                    joined.add(key);
                    added.add(t.get("name"));
                }
                if (added.isEmpty()) {
                    throw new BusinessException("所选沟通对象已在本次沟通中："
                            + String.join("、", skipped) + "，无需重复发起");
                }

                // 会话本来就是进行中，这里只确保待办仍处于锁定态（幂等）
                taskExt.setBizStatus(BizTaskExt.STATUS_COMMUNICATING);
                taskExt.setActiveSessionId(activeSession.getId());
                taskExt.setUpdateTime(new Date());
                extMgr.update(taskExt);

                result.put("sessionId", activeSession.getId());
                result.put("appended", true);
                result.put("addedTargets", added);
                result.put("skippedTargets", skipped);
                result.put("currentRound", activeSession.getCurrentRound());
                return null;
            }

            // ② 没有进行中的会话时才新建会话。
            //    若扩展表仍标着“沟通中”，说明是历史脏数据（会话已结束但状态没回写、
            //    或会话被直接中断），纠正后继续发起，不再把用户挡在门外。
            if (BizTaskExt.STATUS_COMMUNICATING.equals(taskExt.getBizStatus())) {
                taskExt.setBizStatus(BizTaskExt.STATUS_NORMAL);
                taskExt.setActiveSessionId(null);
            }

            String sessionId = UUID.randomUUID().toString().replace("-", "");
            BizCommSession session = new BizCommSession();
            session.setId(sessionId);
            session.setRootTaskId(rootTaskId);
            session.setInitiator(initiator);
            session.setInitialContent(content);
            session.setStatus(BizCommSession.STATUS_ACTIVE);
            session.setCurrentRound(1);
            session.setCreateTime(new Date());
            sessionMgr.insert(session);

            List<String> created = new ArrayList<>();
            for (Map<String, String> t : normalizedTargets) {
                createCommTaskRecord(rootTask, sessionId, null, content, t, extMgr, taskMgr);
                created.add(t.get("name"));
            }

            taskExt.setBizStatus(BizTaskExt.STATUS_COMMUNICATING);
            taskExt.setActiveSessionId(sessionId);
            taskExt.setUpdateTime(new Date());
            extMgr.update(taskExt);

            result.put("sessionId", sessionId);
            result.put("appended", false);
            result.put("addedTargets", created);
            result.put("skippedTargets", Collections.emptyList());
            return null;
        });

        return result;
    }

    /** 沟通对象去重键：类型 + 账号 */
    private static String targetKey(String type, String id) {
        return (type == null ? "" : type.trim().toUpperCase(Locale.ROOT)) + ":"
                + (id == null ? "" : id.trim());
    }

    /**
     * 建一条沟通记录：Camunda 旁路任务 + 扩展记录 + 业务沟通记录。
     *
     * <p>首轮沟通、补充对象、追问（见 {@link #continueCommunication}）三处建单逻辑一致，
     * 抽出来避免三份代码各自漂移。</p>
     */
    private void createCommTaskRecord(Task rootTask, String sessionId, String parentRecordId, String content,
                                      Map<String, String> target,
                                      BizTaskExtManager extMgr, BizCommTaskManager taskMgr) {
        String targetType = target.get("type");
        String targetId = target.get("id");
        String targetName = target.get("name");

        // 创建Camunda原生旁路沟通任务（无executionId），
        // taskDefinitionKey 记为「源节点key#COMM」，便于追溯是哪个节点发起的沟通
        TaskEntity commTask = (TaskEntity) taskService.newTask();
        // 沟通任务沿用源待办（当前待办）的名称，待办列表里与源节点保持一致
        commTask.setName(resolveTaskDisplayName(rootTask));
        commTask.setTaskDefinitionKey(BizCommTask.buildCommTaskKey(rootTask.getTaskDefinitionKey()));
        commTask.setParentTaskId(rootTask.getId());
        commTask.setProcessInstanceId(rootTask.getProcessInstanceId());
        // 必须带上流程定义 ID：standalone 任务若 processDefinitionId 为空，
        // Camunda 解析 taskDefinition 时会退化为按 caseDefinitionId 查 CMMN 定义并抛错
        commTask.setProcessDefinitionId(rootTask.getProcessDefinitionId());
        commTask.setAssignee(targetId);
        commTask.setDescription(content);
        taskService.saveTask(commTask);
        // 任务 ID 由引擎生成，必须在 save 之后取，否则业务表拿到的是空/错值
        String camundaTaskId = commTask.getId();

        // 同时给沟通任务初始化扩展记录
        BizTaskExt commExt = new BizTaskExt();
        commExt.setId(UUID.randomUUID().toString().replace("-", ""));
        commExt.setTaskId(camundaTaskId);
        commExt.setProcessInstanceId(rootTask.getProcessInstanceId());
        commExt.setAssignee(targetId);
        commExt.setBizStatus(BizTaskExt.STATUS_NORMAL);
        commExt.setCreateTime(new Date());
        commExt.setUpdateTime(new Date());
        extMgr.insert(commExt);

        BizCommTask ct = new BizCommTask();
        ct.setId(UUID.randomUUID().toString().replace("-", ""));
        ct.setSessionId(sessionId);
        ct.setParentCommTaskId(parentRecordId);
        ct.setSourceTaskId(rootTask.getId());
        ct.setRootTaskId(rootTask.getId());
        // 单独落一份来源节点 key，报表按节点统计时无需解析 #COMM 后缀
        ct.setSourceTaskDefKey(rootTask.getTaskDefinitionKey());
        ct.setTargetType(targetType);
        ct.setTargetId(targetId);
        ct.setTargetName(targetName);
        ct.setContent(content);
        ct.setStatus(BizCommTask.STATUS_PENDING);
        ct.setCreateTime(new Date());
        // 旁路任务ID 落 CAMUNDA_TASK_ID_，供后续收回/反馈定位任务
        ct.setCamundaTaskId(camundaTaskId);
        // 部门沟通子流程的挂载点（当前未实现，deptProcInstId 保持 null）：
        //   if (BizCommTask.TARGET_DEPT.equals(targetType)) {
        //       ProcessInstance sub = runtimeService.startProcessInstanceByKey("dept_communication_flow", vars);
        //       ct.setDeptProcInstId(sub.getId());
        //   }
        // 两个字段各归各位：任务ID 只进 camundaTaskId，子流程实例ID 只进 deptProcInstId，不再互相顶替。
        taskMgr.insert(ct);
    }

    /**
     * 收回沟通（部分或全部）。
     *
     * <p>两种口径：</p>
     * <ul>
     *   <li><b>发起人</b>：按传入的记录收回其整棵子树；不传记录表示“全部收回”，直接结束本次沟通。</li>
     *   <li><b>上游沟通对象</b>：一次收回自己在这条链上的<b>全部下游未反馈记录</b>（含下级再转交的隔级记录）。
     *       例如 A→B，B→C、D，C→E：B 收回时 C、D、E 一并取消，B 仍可正常反馈，
     *       不需要逐条点击，也不会出现“收了下级却漏了更下一级”。</li>
     * </ul>
     *
     * @return 实际被收回的记录条数
     */
    public int withdrawCommunication(String sessionId, String operator, List<String> commTaskIds) {
        ProcessEngineConfigurationImpl engineConfig = getEngineConfig();
        return engineConfig.getCommandExecutorTxRequired().execute((Command<Integer>) commandContext -> {
            BizCommSessionManager sessionMgr = commandContext.getSession(BizCommSessionManager.class);
            BizCommTaskManager taskMgr = commandContext.getSession(BizCommTaskManager.class);
            BizTaskExtManager extMgr = commandContext.getSession(BizTaskExtManager.class);

            BizCommSession session = sessionMgr.findById(sessionId);
            if (session == null) throw new BusinessException("会话不存在");

            // 一次性查出会话内全部沟通任务，之后全部在内存中判定状态，
            // 避免同一命令内“改了状态再查库”读到旧值（Camunda 命令内不做中途 flush）
            List<BizCommTask> allTasks = taskMgr.findBySessionId(sessionId);
            Map<String, BizCommTask> byId = indexById(allTasks);
            boolean isInitiator = session.getInitiator() != null && session.getInitiator().equals(operator);

            Set<String> toWithdraw = new HashSet<>();
            if (commTaskIds == null || commTaskIds.isEmpty()) {
                // “全部收回”会直接结束本次沟通，仍然只允许发起人操作
                if (!isInitiator) {
                    throw new BusinessException("仅沟通发起人可一次性收回全部沟通");
                }
                for (BizCommTask t : allTasks) {
                    if (BizCommTask.STATUS_PENDING.equals(t.getStatus())) {
                        collectChildren(allTasks, t.getId(), toWithdraw);
                    }
                }
            } else if (isInitiator) {
                for (String tid : commTaskIds) {
                    BizCommTask root = byId.get(tid);
                    if (root == null) {
                        throw new BusinessException("沟通记录不存在或不属于本次沟通");
                    }
                    collectChildren(allTasks, tid, toWithdraw);
                }
            } else {
                // 上游沟通对象：一次收回自己下游的全部未反馈记录，而不是只收回被点击的那一条。
                // A→B、B→C/D、C→E 时，B 收回应当把 C、D、E 一起取消，B 自己那条保留以便继续反馈。
                Set<String> ownRecordIds = new HashSet<>();
                for (BizCommTask t : allTasks) {
                    if (operator.equals(t.getTargetId())) {
                        ownRecordIds.add(t.getId());
                    }
                }
                if (ownRecordIds.isEmpty()) {
                    throw new BusinessException("仅沟通发起人或上游沟通对象可收回");
                }
                Set<String> downstream = new HashSet<>();
                for (String ownId : ownRecordIds) {
                    collectChildren(allTasks, ownId, downstream);
                }
                downstream.removeAll(ownRecordIds);

                // 传入的记录必须落在自己的下游里，避免借这个接口收回别人的分支
                for (String tid : commTaskIds) {
                    if (!downstream.contains(tid)) {
                        BizCommTask root = byId.get(tid);
                        if (root == null) {
                            throw new BusinessException("沟通记录不存在或不属于本次沟通");
                        }
                        throw new BusinessException("只能收回自己下游的沟通（"
                                + commTargetLabel(root) + " 不在你的下游）");
                    }
                }
                boolean hasPendingDownstream = false;
                for (String id : downstream) {
                    BizCommTask d = byId.get(id);
                    if (d != null && BizCommTask.STATUS_PENDING.equals(d.getStatus())) {
                        hasPendingDownstream = true;
                        break;
                    }
                }
                if (!hasPendingDownstream) {
                    throw new BusinessException("你没有待反馈的下游沟通，请直接提交反馈");
                }
                toWithdraw.addAll(downstream);
            }

            int withdrawn = 0;
            Date withdrawnAt = new Date();
            for (String tid : toWithdraw) {
                BizCommTask ct = taskMgr.findById(tid);
                if (ct == null) continue;
                if (!BizCommTask.STATUS_PENDING.equals(ct.getStatus())) continue;
                ct.setStatus(BizCommTask.STATUS_WITHDRAWN);
                // 引擎任务删除后历史里什么都不剩，收回时间只能记在业务表上
                ct.setWithdrawTime(withdrawnAt);
                taskMgr.update(ct);
                withdrawn++;
                // 删除对应的Camunda沟通任务（cascade=true级联删除子任务）
                String camundaTaskId = ct.getCamundaTaskId();
                if (camundaTaskId != null) {
                    taskService.deleteTask(camundaTaskId, true);
                }
            }

            // 会话内仍有未处理（PENDING）的沟通任务时保持锁定：直接用内存快照判断
            boolean stillPending = false;
            for (BizCommTask t : allTasks) {
                if (toWithdraw.contains(t.getId())) continue;
                if (BizCommTask.STATUS_PENDING.equals(t.getStatus())) {
                    stillPending = true;
                    break;
                }
            }
            if (!stillPending) {
                session.setStatus(BizCommSession.STATUS_COMPLETED);
                session.setFinishTime(new Date());
                sessionMgr.update(session);

                BizTaskExt ext = extMgr.findByTaskId(session.getRootTaskId());
                if (ext != null) {
                    ext.setBizStatus(BizTaskExt.STATUS_NORMAL);
                    ext.setActiveSessionId(null);
                    ext.setUpdateTime(new Date());
                    extMgr.update(ext);
                }
            }
            return withdrawn;
        });
    }

    private void collectChildren(List<BizCommTask> allTasks, String taskId, Set<String> result) {
        if (!result.add(taskId)) return;
        for (BizCommTask c : allTasks) {
            if (taskId.equals(c.getParentCommTaskId())) {
                collectChildren(allTasks, c.getId(), result);
            }
        }
    }

    private static Map<String, BizCommTask> indexById(List<BizCommTask> tasks) {
        Map<String, BizCommTask> byId = new HashMap<>();
        if (tasks != null) {
            for (BizCommTask t : tasks) {
                if (t.getId() != null) {
                    byId.put(t.getId(), t);
                }
            }
        }
        return byId;
    }

    /**
     * 操作人是否位于这条沟通记录的“上游”：即从该记录沿父链向上，任一严格祖先的接收人。
     *
     * <p>语义是“谁把沟通（或它的上游）转出去，谁就能收回整条下游链”，而不只是直接子记录：
     * A 发起给 B，B 转给 C 与 D，C 再转给 E，则 A 可收回 B/C/D/E，B 可收回 C/D/E，C 可收回 E。
     * 中间人（如 B）因此不会因为下级再往下转就失去收回能力。</p>
     *
     * <p>记录自身的接收人不在判定范围内（他自己只能反馈或继续转交），
     * 遍历带 visited 保护，历史脏数据成环也不会死循环。</p>
     */
    private boolean isUpstreamOf(BizCommTask record, Map<String, BizCommTask> byId, String operator) {
        if (record == null || operator == null || byId == null) {
            return false;
        }
        Set<String> visited = new HashSet<>();
        String parentId = record.getParentCommTaskId();
        while (parentId != null && visited.add(parentId)) {
            BizCommTask parent = byId.get(parentId);
            if (parent == null) {
                return false;
            }
            if (operator.equals(parent.getTargetId())) {
                return true;
            }
            parentId = parent.getParentCommTaskId();
        }
        return false;
    }

    /**
     * 单条沟通记录是否可由 operator 收回：会话发起人，或该条记录的上游沟通对象（父链上任一祖先的接收人）。
     * 与 {@link #withdrawCommunication} 的校验保持同一口径，前端按钮据此显示。
     *
     * <p>注意上游收回的<b>执行范围</b>是整个下游链：按钮虽然挂在某一条记录上，
     * 点下去会把自己下游全部未反馈记录一起取消（A→B、B→C/D、C→E 时 B 收回即取消 C、D、E）。</p>
     */
    private boolean canWithdrawRecord(BizCommTask record, BizCommSession session, String operator,
                                      Map<String, BizCommTask> byId) {
        if (record == null || operator == null
                || !BizCommTask.STATUS_PENDING.equals(record.getStatus())) {
            return false;
        }
        if (session != null && session.getInitiator() != null
                && session.getInitiator().equals(operator)) {
            return true;
        }
        return isUpstreamOf(record, byId, operator);
    }

    /**
     * 批量计算会话内每条记录当前用户能否收回（沟通反馈界面的“同轮反馈进度”直接用）。
     */
    public Map<String, Boolean> withdrawableRecordMap(String sessionId, String operator) {
        return getEngineConfig().getCommandExecutorTxRequired().execute(
                (Command<Map<String, Boolean>>) commandContext -> {
                    Map<String, Boolean> result = new HashMap<>();
                    BizCommSessionManager sessionMgr = commandContext.getSession(BizCommSessionManager.class);
                    BizCommTaskManager taskMgr = commandContext.getSession(BizCommTaskManager.class);
                    BizCommSession session = sessionMgr.findById(sessionId);
                    if (session == null || !BizCommSession.STATUS_ACTIVE.equals(session.getStatus())) {
                        return result;
                    }
                    List<BizCommTask> allTasks = taskMgr.findBySessionId(sessionId);
                    Map<String, BizCommTask> byId = indexById(allTasks);
                    for (BizCommTask t : allTasks) {
                        result.put(t.getId(), canWithdrawRecord(t, session, operator, byId));
                    }
                    return result;
                });
    }

    /** 允许的沟通对象类型，与表 BIZ_COMM_TASK_.TARGET_TYPE_ 的取值保持一致 */
    private static final Set<String> SUPPORTED_TARGET_TYPES =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("USER", "DEPT")));

    /**
     * 归一化沟通对象。
     *
     * <p>接口文档约定用 {@code {"type","id","name"}}，但前端人员选择器常直接提交
     * {@code {"type","name","value"}}（name 是账号、value 是显示名）。两种格式都在这里收敛：
     * 账号标识按 id → userId → targetId → name → value 取第一个非空值，
     * 显示名按 displayName → targetName → value → name 取第一个非空值。</p>
     *
     * <p>解析不出账号时抛业务异常，而不是让 null 落到 TARGET_ID_（NOT NULL）上引发 500。</p>
     */
    private List<Map<String, String>> normalizeTargets(List<Map<String, String>> rawTargets) {
        if (rawTargets == null || rawTargets.isEmpty()) {
            throw new BusinessException("请至少选择一个沟通对象");
        }
        List<Map<String, String>> result = new ArrayList<>(rawTargets.size());
        for (int i = 0; i < rawTargets.size(); i++) {
            Map<String, String> raw = rawTargets.get(i);
            String label = "第" + (i + 1) + "个沟通对象";
            if (raw == null) {
                throw new BusinessException(label + "为空");
            }

            String type = firstNonBlank(raw.get("type"), "USER").toUpperCase(Locale.ROOT);
            if (!SUPPORTED_TARGET_TYPES.contains(type)) {
                throw new BusinessException(label + "的类型不支持：" + type + "（仅支持 USER / DEPT）");
            }

            String targetId = firstNonBlank(raw.get("id"), raw.get("userId"),
                    raw.get("targetId"), raw.get("name"), raw.get("value"));
            if (targetId == null) {
                throw new BusinessException(label + "缺少账号标识，请提供 id（或 name/value）");
            }

            String targetName = firstNonBlank(raw.get("displayName"), raw.get("targetName"),
                    raw.get("value"), raw.get("name"), targetId);

            Map<String, String> normalized = new HashMap<>(4);
            normalized.put("type", type);
            normalized.put("id", targetId);
            normalized.put("name", targetName);
            result.add(normalized);
        }
        return result;
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.trim().isEmpty()) {
                return c.trim();
            }
        }
        return null;
    }

    /**
     * 反馈沟通意见
     */
    public void replyCommTask(String commTaskRecordId, String userId, String replyContent) {
        ProcessEngineConfigurationImpl engineConfig = getEngineConfig();
        engineConfig.getCommandExecutorTxRequired().execute((Command<Void>) commandContext -> {
            BizCommTaskManager taskMgr = commandContext.getSession(BizCommTaskManager.class);
            BizCommSessionManager sessionMgr = commandContext.getSession(BizCommSessionManager.class);
            BizTaskExtManager extMgr = commandContext.getSession(BizTaskExtManager.class);

            BizCommTask ct = taskMgr.findById(commTaskRecordId);
            if (ct == null) throw new BusinessException("沟通任务不存在");
            // 身份校验：只有该条沟通的接收人本人可反馈，杜绝代填/伪造反馈
            if (userId == null || !userId.equals(ct.getTargetId())) {
                throw new BusinessException("仅沟通对象本人可反馈该条沟通");
            }
            if (!BizCommTask.STATUS_PENDING.equals(ct.getStatus())) {
                throw new BusinessException("该沟通已处理或已收回");
            }

            // 先取会话快照，再基于快照做校验与判断（避免写后读）
            List<BizCommTask> allTasks = taskMgr.findBySessionId(ct.getSessionId());

            // 已经把问题转给别人（或追问）且对方还没反馈时，本条沟通不能先“完成反馈”：
            // 否则上级记录先办结、子沟通后续反馈就失去承接点，也会让人误以为整条链已闭环。
            BizCommTask pendingChild = findPendingDescendant(ct.getId(), allTasks);
            if (pendingChild != null) {
                throw new BusinessException("还有未反馈的沟通（"
                        + commTargetLabel(pendingChild) + "），请等对方反馈，或由发起人/上游沟通对象收回后再提交反馈");
            }

            ct.setStatus(BizCommTask.STATUS_REPLIED);
            ct.setReplyContent(replyContent);
            ct.setReplyUserId(userId);
            ct.setReplyTime(new Date());
            taskMgr.update(ct);
            boolean stillPending = false;
            for (BizCommTask t : allTasks) {
                if (t.getId().equals(ct.getId())) continue;
                if (BizCommTask.STATUS_PENDING.equals(t.getStatus())) {
                    stillPending = true;
                    break;
                }
            }

            // 结束对应的 Camunda 旁路待办：反馈属于“办结”，必须 complete，不能 deleteTask。
            // deleteTask 只会把任务置为删除态（DELETE_REASON_ 有值），
            // 已办列表据此显示“已收回/删除”，且不会产生任何审批意见记录。
            String camundaTaskId = ct.getCamundaTaskId();
            if (camundaTaskId != null) {
                Task commTask = taskService.createTaskQuery().taskId(camundaTaskId).singleResult();
                if (commTask != null) {
                    // ① 先落审批意见：任务 complete 之后同一 taskId 已结束，
                    //    再 createComment 会失败；且意见必须挂在当前 taskId 上，
                    //    已办详情/流程图按 taskId 查评论才能一致显示。
                    String opinion = replyContent == null ? null : replyContent.trim();
                    if (!opinion.isEmpty()) {
                        // createComment 的第二个参数是流程实例ID（不是 userId）。
                        // 这里传旁路任务所属流程实例，意见才能按流程/任务双向查回。
                        taskService.createComment(camundaTaskId, commTask.getProcessInstanceId(), opinion);
                    }
                    // ② 办结任务：任务为 standalone（newTask 创建、无 executionId），
                    //    complete 只结束该旁路待办并写入历史（DELETE_REASON_ 为 null），
                    //    不会推进主流程，也不会影响源待办。
                    taskService.complete(camundaTaskId);
                }
            }

            // 会话内没有待处理沟通任务时（含多轮子沟通）才解锁主待办
            if (!stillPending) {
                BizCommSession session = sessionMgr.findById(ct.getSessionId());
                if (session != null) {
                    session.setStatus(BizCommSession.STATUS_COMPLETED);
                    session.setFinishTime(new Date());
                    sessionMgr.update(session);

                    BizTaskExt ext = extMgr.findByTaskId(session.getRootTaskId());
                    if (ext != null) {
                        ext.setBizStatus(BizTaskExt.STATUS_NORMAL);
                        ext.setActiveSessionId(null);
                        ext.setUpdateTime(new Date());
                        extMgr.update(ext);
                    }
                }
            }
            return null;
        });
    }

    /**
     * 找出该沟通记录下（任意层级）仍在等待反馈的子沟通。
     *
     * <p>父子关系来自 {@code PARENT_COMM_TASK_ID_}：沟通对象可以把问题转给他人，
     * 被转交人还能继续往下转，因此这里按广度优先遍历整棵子树，而不是只看直接子记录。</p>
     *
     * @return 第一条仍未反馈的后代记录；没有则返回 null
     */
    private BizCommTask findPendingDescendant(String recordId, List<BizCommTask> allTasks) {
        if (recordId == null || allTasks == null || allTasks.isEmpty()) {
            return null;
        }
        Map<String, List<BizCommTask>> childrenByParent = new HashMap<>();
        for (BizCommTask t : allTasks) {
            if (t.getParentCommTaskId() == null) {
                continue;
            }
            childrenByParent.computeIfAbsent(t.getParentCommTaskId(), k -> new ArrayList<>()).add(t);
        }
        Set<String> visited = new HashSet<>();
        Deque<BizCommTask> queue =
                new ArrayDeque<>(childrenByParent.getOrDefault(recordId, Collections.emptyList()));
        while (!queue.isEmpty()) {
            BizCommTask child = queue.poll();
            if (child.getId() != null && !visited.add(child.getId())) {
                continue;
            }
            if (BizCommTask.STATUS_PENDING.equals(child.getStatus())) {
                return child;
            }
            queue.addAll(childrenByParent.getOrDefault(child.getId(), Collections.emptyList()));
        }
        return null;
    }

    /** 沟通对象展示名：优先姓名，缺失时回退账号 */
    private static String commTargetLabel(BizCommTask record) {
        String name = record.getTargetName();
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        return record.getTargetId() == null ? "沟通对象" : record.getTargetId();
    }

    /**
     * 收集一条沟通记录所有上游（父、祖父……）的接收人账号。
     *
     * <p>转交时不允许再发给上游对象：上游自己那条沟通仍在待反馈，转回去会形成
     * “你等我、我等你”的环，谁也提交不了反馈，只能由发起人收回。</p>
     */
    private Set<String> collectUpstreamTargetIds(BizCommTask record, BizCommTaskManager taskMgr) {
        Set<String> ids = new HashSet<>();
        Set<String> visited = new HashSet<>();
        BizCommTask current = record;
        while (current != null && current.getParentCommTaskId() != null) {
            if (current.getId() != null && !visited.add(current.getId())) {
                break;
            }
            BizCommTask parent = taskMgr.findById(current.getParentCommTaskId());
            if (parent == null) {
                break;
            }
            if (parent.getTargetId() != null) {
                ids.add(parent.getTargetId());
            }
            current = parent;
        }
        return ids;
    }

    /**
     * 沟通任务显示名：沿用源待办的名称。
     * 节点未配置 name 时 Camunda 返回 null，直接使用会在待办列表出现空标题，
     * 因此回退到节点定义 key，最后兜底为固定文案，保证列表永远有可读标题。
     */
    private static String resolveTaskDisplayName(Task sourceTask) {
        if (sourceTask == null) {
            return "沟通";
        }
        String name = sourceTask.getName();
        if (name != null && !name.trim().isEmpty()) {
            return name;
        }
        String taskDefinitionKey = sourceTask.getTaskDefinitionKey();
        if (taskDefinitionKey != null && !taskDefinitionKey.isEmpty()) {
            return taskDefinitionKey;
        }
        return "沟通";
    }

    /**
     * 继续沟通
     */
    public void continueCommunication(String parentCommTaskRecordId, String userId, String content,
                                      List<Map<String, String>> targets) {
        ProcessEngineConfigurationImpl engineConfig = getEngineConfig();
        engineConfig.getCommandExecutorTxRequired().execute((Command<Void>) commandContext -> {
            BizCommTaskManager taskMgr = commandContext.getSession(BizCommTaskManager.class);
            BizCommSessionManager sessionMgr = commandContext.getSession(BizCommSessionManager.class);
            BizTaskExtManager extMgr = commandContext.getSession(BizTaskExtManager.class);

            BizCommTask parent = taskMgr.findById(parentCommTaskRecordId);
            if (parent == null) throw new BusinessException("父沟通任务不存在");

            BizCommSession session = sessionMgr.findById(parent.getSessionId());
            if (session == null) throw new BusinessException("父沟通任务所属会话不存在");
            // 身份校验：追问等同于继续发起沟通，只有原发起人可操作
            if (userId == null || !userId.equals(session.getInitiator())) {
                throw new BusinessException("仅沟通发起人可继续沟通");
            }
            if (targets == null || targets.isEmpty()) {
                throw new BusinessException("请至少选择一个沟通对象");
            }
            List<Map<String, String>> normalizedTargets = normalizeTargets(targets);

            // 先确认源待办仍存在：已结束的待办再建旁路任务会得到 processDefinitionId=null，
            // Camunda 解析 taskDefinition 时会退化为按 caseDefinitionId 查 CMMN 并抛错。
            Task rootTask = taskService.createTaskQuery().taskId(session.getRootTaskId()).singleResult();
            if (rootTask == null) {
                throw new BusinessException("源待办已结束，无法继续沟通");
            }
            String procInstId = rootTask.getProcessInstanceId();
            String procDefId = rootTask.getProcessDefinitionId();

            session.setCurrentRound(session.getCurrentRound() + 1);
            session.setStatus(BizCommSession.STATUS_ACTIVE);
            sessionMgr.update(session);

            // 确保待办锁定
            BizTaskExt ext = extMgr.findByTaskId(session.getRootTaskId());
            if (ext != null) {
                ext.setBizStatus(BizTaskExt.STATUS_COMMUNICATING);
                ext.setActiveSessionId(session.getId());
                ext.setUpdateTime(new Date());
                extMgr.update(ext);
            }

            createFollowUpRecords(rootTask, parent, session, content, normalizedTargets, extMgr, taskMgr);
            return null;
        });
    }

    /**
     * 沟通对象在反馈界面“发起沟通”：把当前这条沟通转给其他人补充核实。
     *
     * <p>与原发起人的“追问”共用同一套建单逻辑，区别只在入口与权限：
     * 追问要求操作人是会话发起人，这里是<b>该条沟通的接收人本人</b>。
     * 新记录挂在当前记录下（{@code PARENT_COMM_TASK_ID_}），所以审批界面的追问树、
     * 流程跟踪页的会话分组都能顺着父链看到“谁转给了谁”，不会出现孤立记录。</p>
     *
     * <p>当前记录保持 PENDING，接收人转交后仍可以继续填写自己的反馈。</p>
     */
    public Map<String, Object> forwardCommunication(String commRecordId, String operator, String content,
                                                    List<Map<String, String>> targets) {
        ProcessEngineConfigurationImpl engineConfig = getEngineConfig();
        Map<String, Object> result = new LinkedHashMap<>();
        engineConfig.getCommandExecutorTxRequired().execute((Command<Void>) commandContext -> {
            BizCommTaskManager taskMgr = commandContext.getSession(BizCommTaskManager.class);
            BizCommSessionManager sessionMgr = commandContext.getSession(BizCommSessionManager.class);
            BizTaskExtManager extMgr = commandContext.getSession(BizTaskExtManager.class);

            BizCommTask parent = taskMgr.findById(commRecordId);
            if (parent == null) {
                throw new BusinessException("沟通记录不存在");
            }
            // 身份校验：只有这条沟通的接收人本人可以把沟通转给其他人
            if (operator == null || !operator.equals(parent.getTargetId())) {
                throw new BusinessException("仅当前沟通对象本人可再次发起沟通");
            }
            if (!BizCommTask.STATUS_PENDING.equals(parent.getStatus())) {
                throw new BusinessException("该沟通已处理或已收回，无法再次发起沟通");
            }
            BizCommSession session = sessionMgr.findById(parent.getSessionId());
            if (session == null) {
                throw new BusinessException("该沟通所属会话不存在");
            }
            String normalizedContent = firstNonBlank(content);
            if (normalizedContent == null) {
                throw new BusinessException("请填写沟通内容");
            }
            List<Map<String, String>> normalizedTargets = normalizeTargets(targets);
            // 上游对象（把沟通转给我的人，以及更上层的人）不能作为本次沟通对象：
            // 他们自己的那条沟通仍在待反馈，转回去会形成互相等待的环。
            Set<String> upstreamTargetIds = collectUpstreamTargetIds(parent, taskMgr);
            String initiator = session.getInitiator();
            for (Map<String, String> t : normalizedTargets) {
                String targetId = t.get("id");
                // 转给自己没有意义：既不会产生新的待办，也会让追问链自我引用
                if (operator.equals(targetId)) {
                    throw new BusinessException("不能将沟通发起给自己，请选择其他人员");
                }
                // 发起人只负责发起和收回，不承接沟通；转给发起人等同于把问题退回原点
                if (initiator != null && initiator.equals(targetId)) {
                    throw new BusinessException("不能把沟通再发给发起人（" + initiator
                            + "），请直接完成反馈，或转给其他人员");
                }
                if (upstreamTargetIds.contains(targetId)) {
                    throw new BusinessException("不能把沟通转回给上游沟通对象（" + t.get("name")
                            + "），请直接完成反馈，或转给其他人员");
                }
            }

            // 源待办必须仍存在：已结束的待办再建旁路任务会得到 processDefinitionId=null，
            // Camunda 解析 taskDefinition 时会退化为按 caseDefinitionId 查 CMMN 并抛错。
            Task rootTask = taskService.createTaskQuery().taskId(parent.getRootTaskId()).singleResult();
            if (rootTask == null) {
                throw new BusinessException("源待办已结束，无法再次发起沟通");
            }

            session.setCurrentRound(session.getCurrentRound() + 1);
            session.setStatus(BizCommSession.STATUS_ACTIVE);
            sessionMgr.update(session);

            // 保持源待办锁定：转交期间原审批人仍不能审批
            BizTaskExt ext = extMgr.findByTaskId(session.getRootTaskId());
            if (ext != null) {
                ext.setBizStatus(BizTaskExt.STATUS_COMMUNICATING);
                ext.setActiveSessionId(session.getId());
                ext.setUpdateTime(new Date());
                extMgr.update(ext);
            }

            List<String> created = createFollowUpRecords(rootTask, parent, session,
                    normalizedContent, normalizedTargets, extMgr, taskMgr);

            result.put("sessionId", session.getId());
            result.put("currentRound", session.getCurrentRound());
            result.put("addedTargets", created);
            return null;
        });
        return result;
    }

    /**
     * 为首轮之后的沟通建单（追问 / 转交共用）。
     * 每条记录同时创建 Camunda 旁路任务与扩展记录，父链指向 {@code parent}。
     */
    private List<String> createFollowUpRecords(Task rootTask, BizCommTask parent, BizCommSession session,
                                               String content, List<Map<String, String>> targets,
                                               BizTaskExtManager extMgr, BizCommTaskManager taskMgr) {
        String procInstId = rootTask.getProcessInstanceId();
        String procDefId = rootTask.getProcessDefinitionId();
        int round = session.getCurrentRound();
        List<String> created = new ArrayList<>(targets.size());
        for (Map<String, String> t : targets) {
            String newRecordId = UUID.randomUUID().toString().replace("-", "");

            TaskEntity commTask = (TaskEntity) taskService.newTask();
            // 追问同样沿用源待办名称，与源节点保持一致；用 description 保留“追问 + 轮次 + 内容”，
            // 前端/报表需要区分首轮与追问时读 description，不必依赖任务名
            commTask.setName(resolveTaskDisplayName(rootTask));
            commTask.setDescription("沟通追问（第 " + round + " 轮）：" + content);
            commTask.setTaskDefinitionKey(BizCommTask.buildCommTaskKey(rootTask.getTaskDefinitionKey()));
            commTask.setParentTaskId(parent.getSourceTaskId());
            if (procInstId != null) {
                commTask.setProcessInstanceId(procInstId);
            }
            if (procDefId != null) {
                commTask.setProcessDefinitionId(procDefId);
            }
            commTask.setAssignee(t.get("id"));
            taskService.saveTask(commTask);
            String newCamundaTaskId = commTask.getId();

            BizTaskExt commExt = new BizTaskExt();
            commExt.setId(UUID.randomUUID().toString().replace("-", ""));
            commExt.setTaskId(newCamundaTaskId);
            commExt.setProcessInstanceId(procInstId);
            commExt.setAssignee(t.get("id"));
            commExt.setBizStatus(BizTaskExt.STATUS_NORMAL);
            commExt.setCreateTime(new Date());
            commExt.setUpdateTime(new Date());
            extMgr.insert(commExt);

            BizCommTask ct = new BizCommTask();
            ct.setId(newRecordId);
            ct.setSessionId(parent.getSessionId());
            ct.setParentCommTaskId(parent.getId());
            ct.setSourceTaskId(parent.getSourceTaskId());
            ct.setRootTaskId(parent.getRootTaskId());
            // 追问同样归属会话发起节点；历史父记录可能没有该字段，回退到父记录的上游节点
            ct.setSourceTaskDefKey(rootTask.getTaskDefinitionKey() != null
                    ? rootTask.getTaskDefinitionKey() : parent.getSourceTaskDefKey());
            ct.setTargetType(t.get("type"));
            ct.setTargetId(t.get("id"));
            ct.setTargetName(t.get("name"));
            ct.setContent(content);
            ct.setStatus(BizCommTask.STATUS_PENDING);
            ct.setCreateTime(new Date());
            ct.setCamundaTaskId(newCamundaTaskId);
            taskMgr.insert(ct);
            created.add(t.get("name"));
        }
        return created;
    }

    /**
     * 取沟通记录的来源节点 key。
     * 新记录直接读字段；历史记录（字段为空）从旁路任务 key 还原，再退到根待办的节点 key。
     */
    private String resolveSourceTaskDefKey(BizCommTask ct, String rootTaskId) {
        if (ct.getSourceTaskDefKey() != null) {
            return ct.getSourceTaskDefKey();
        }
        String fromCommTask = BizCommTask.parseSourceTaskDefKey(taskDefinitionKeyOf(ct.getCamundaTaskId()));
        if (fromCommTask != null) {
            return fromCommTask;
        }
        return BizCommTask.parseSourceTaskDefKey(taskDefinitionKeyOf(rootTaskId));
    }

    /** 查询任务节点 key，任务不存在时返回 null（不抛异常） */
    private String taskDefinitionKeyOf(String taskId) {
        if (taskId == null) {
            return null;
        }
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        return task == null ? null : task.getTaskDefinitionKey();
    }

    /**
     * 查询待办沟通状态
     */
    public Map<String, Object> getTaskCommStatus(String taskId, String currentUserId) {
        ProcessEngineConfigurationImpl engineConfig = getEngineConfig();
        return engineConfig.getCommandExecutorTxRequired().execute((Command<Map<String, Object>>) commandContext -> {
            BizTaskExtManager extMgr = commandContext.getSession(BizTaskExtManager.class);
            BizCommSessionManager sessionMgr = commandContext.getSession(BizCommSessionManager.class);
            BizCommTaskManager taskMgr = commandContext.getSession(BizCommTaskManager.class);

            BizTaskExt ext = extMgr.findByTaskId(taskId);
            Map<String, Object> vo = new LinkedHashMap<>();
            vo.put("taskId", taskId);

            Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
            boolean isCommTask = task != null && BizCommTask.isCommTaskKey(task.getTaskDefinitionKey());
            vo.put("isCommTask", isCommTask);
            // 待办本身是追问任务时直接给出标识，前端无需再解析 taskDefinitionKey
            boolean isFollowUp = false;
            BizCommTask selfRecord = null;
            if (isCommTask) {
                selfRecord = taskMgr.findByCamundaTaskId(taskId);
                isFollowUp = selfRecord != null && selfRecord.getParentCommTaskId() != null;
            }
            vo.put("isFollowUp", isFollowUp);

            String bizStatus = ext == null ? BizTaskExt.STATUS_NORMAL : ext.getBizStatus();
            String activeSessionId = ext == null ? null : ext.getActiveSessionId();
            vo.put("bizStatus", bizStatus);
            vo.put("canApprove", BizTaskExt.STATUS_NORMAL.equals(bizStatus) && !isCommTask);
            vo.put("canInitiate", !isCommTask && task != null
                    && currentUserId != null && currentUserId.equals(task.getAssignee()));
            // 沟通待办本身也能“再发起沟通”：由当前沟通对象把问题转给其他人补充核实，
            // 新记录挂在当前沟通记录下，仍属于同一会话（见 forwardCommunication）。
            vo.put("canForwardComm", isCommTask && selfRecord != null
                    && BizCommTask.STATUS_PENDING.equals(selfRecord.getStatus())
                    && currentUserId != null && currentUserId.equals(selfRecord.getTargetId()));
            vo.put("canWithdraw", false);
            vo.put("activeSessionId", activeSessionId);

            // 会话进行中优先取 activeSession；结束后 activeSessionId 会被清空，
            // 此时回退到该待办最近一次会话，保证沟通内容/对象/回复事后仍可查。
            BizCommSession session = null;
            boolean sessionActive = false;
            if (activeSessionId != null) {
                session = sessionMgr.findById(activeSessionId);
                sessionActive = session != null;
            }
            if (session == null) {
                session = sessionMgr.findLatestByRootTask(taskId);
                sessionActive = false;
            }

            // 同一待办可以先后发起多次沟通，每次“发起沟通”都会生成一个新会话。
            // 审批界面不能只回“当前/最新一次”，否则前面几轮沟通会整体消失。
            List<BizCommSession> allSessions = sessionMgr.findByRootTask(taskId);
            if (allSessions == null || allSessions.isEmpty()) {
                allSessions = session == null
                        ? Collections.emptyList() : Collections.singletonList(session);
            }

            // 先一次性取出全部会话的沟通记录，父记录跨会话时也能找到
            Map<String, List<BizCommTask>> tasksBySession = new LinkedHashMap<>();
            Map<String, BizCommTask> recordById = new HashMap<>();
            for (BizCommSession s : allSessions) {
                List<BizCommTask> records = taskMgr.findBySessionId(s.getId());
                tasksBySession.put(s.getId(), records);
                for (BizCommTask ct : records) {
                    if (ct.getId() != null) {
                        recordById.put(ct.getId(), ct);
                    }
                }
            }

            List<Map<String, Object>> sessionVOList = new ArrayList<>();
            List<Map<String, Object>> allTaskVOList = new ArrayList<>();
            for (int i = 0; i < allSessions.size(); i++) {
                BizCommSession s = allSessions.get(i);
                int seq = i + 1;
                boolean sessionIsActive = sessionActive && activeSessionId != null
                        && activeSessionId.equals(s.getId());
                List<Map<String, Object>> sessionTaskVOList = new ArrayList<>();
                for (BizCommTask ct : tasksBySession.getOrDefault(s.getId(), Collections.emptyList())) {
                    Map<String, Object> tvo = toCommTaskVO(ct, s, seq, sessionIsActive,
                            recordById, currentUserId, taskId);
                    sessionTaskVOList.add(tvo);
                    allTaskVOList.add(tvo);
                }
                Map<String, Object> svo = new LinkedHashMap<>();
                svo.put("sessionId", s.getId());
                svo.put("seq", seq);
                svo.put("status", s.getStatus());
                svo.put("active", sessionIsActive);
                svo.put("initiator", s.getInitiator());
                svo.put("initialContent", s.getInitialContent());
                svo.put("currentRound", s.getCurrentRound());
                svo.put("createTime", s.getCreateTime());
                svo.put("finishTime", s.getFinishTime());
                svo.put("commTasks", sessionTaskVOList);
                sessionVOList.add(svo);
            }
            vo.put("sessions", sessionVOList);

            if (session == null) {
                vo.put("sessionId", null);
                vo.put("sessionActive", false);
                vo.put("sessionStatus", null);
                vo.put("initiator", null);
                vo.put("initialContent", null);
                vo.put("currentRound", null);
                vo.put("createTime", null);
                vo.put("finishTime", null);
            } else {
                boolean isActiveSession = sessionActive && activeSessionId != null
                        && activeSessionId.equals(session.getId());
                vo.put("sessionId", session.getId());
                vo.put("sessionStatus", session.getStatus());
                vo.put("sessionActive", isActiveSession);
                vo.put("initiator", session.getInitiator());
                vo.put("initialContent", session.getInitialContent());
                vo.put("currentRound", session.getCurrentRound());
                vo.put("createTime", session.getCreateTime());
                vo.put("finishTime", session.getFinishTime());
                vo.put("canWithdraw", isActiveSession
                        && session.getInitiator().equals(currentUserId)
                        && BizTaskExt.STATUS_COMMUNICATING.equals(bizStatus));
            }
            // 顶层 commTasks 返回全部会话的记录合集，保证“多次沟通”一条都不丢；
            // 每条记录自带 sessionId/sessionSeq，前端可按会话分组展示。
            vo.put("commTasks", allTaskVOList);

            // 沟通待办能否“提交反馈完成”：本人、待反馈、且下游没有仍在等待反馈的沟通。
            // 这里与 replyCommTask 的校验保持同一口径，前端按钮据此禁用并给出原因。
            boolean canReply = false;
            String replyBlockedBy = null;
            if (isCommTask && selfRecord != null
                    && BizCommTask.STATUS_PENDING.equals(selfRecord.getStatus())
                    && currentUserId != null && currentUserId.equals(selfRecord.getTargetId())) {
                List<BizCommTask> selfSessionTasks = tasksBySession.get(selfRecord.getSessionId());
                if (selfSessionTasks == null) {
                    selfSessionTasks = taskMgr.findBySessionId(selfRecord.getSessionId());
                }
                BizCommTask pendingChild = findPendingDescendant(selfRecord.getId(), selfSessionTasks);
                canReply = pendingChild == null;
                replyBlockedBy = pendingChild == null ? null : commTargetLabel(pendingChild);
            }
            vo.put("canReply", canReply);
            vo.put("replyBlockedBy", replyBlockedBy);

            // 只有“真实存在的流程待办”才谈得上继续沟通：
            // 沟通旁路任务本身、以及已完成的待办（task == null）都不应返回可继续沟通
            vo.put("canContinueComm", !isCommTask && task != null);
            return vo;
        });
    }

    /**
     * 构建单条沟通记录 VO，并带上所属会话信息。
     * <p>前端据此按“第 N 次沟通”分组渲染，同一待办多次沟通不会互相覆盖。</p>
     */
    private Map<String, Object> toCommTaskVO(BizCommTask ct, BizCommSession session, int seq,
                                             boolean sessionActive, Map<String, BizCommTask> recordById,
                                             String currentUserId, String rootTaskId) {
        Map<String, Object> tvo = new LinkedHashMap<>();
        tvo.put("id", ct.getId());
        tvo.put("sessionId", session.getId());
        tvo.put("sessionSeq", seq);
        tvo.put("sessionStatus", session.getStatus());
        tvo.put("sessionActive", sessionActive);
        tvo.put("sessionInitiator", session.getInitiator());
        tvo.put("sessionInitialContent", session.getInitialContent());
        tvo.put("sessionCreateTime", session.getCreateTime());
        tvo.put("parentCommTaskId", ct.getParentCommTaskId());
        // 有父沟通记录即为追问，便于前端列表直接区分“首轮沟通 / 追问”
        tvo.put("isFollowUp", ct.getParentCommTaskId() != null);
        // 父级快照：追问关系不能只靠前端按 id 自行匹配。父记录可能不在本次
        // 会话返回范围内（跨会话追问、父记录被清理），前端就还原不出层级。
        // 这里直接带回父级的对象名与沟通内容，前端无父记录时也能说明“追问自谁”。
        BizCommTask parentRecord = ct.getParentCommTaskId() == null
                ? null : recordById.get(ct.getParentCommTaskId());
        tvo.put("parentTargetName", parentRecord == null ? null : parentRecord.getTargetName());
        tvo.put("parentContent", parentRecord == null ? null : parentRecord.getContent());
        tvo.put("parentInList", parentRecord != null);
        tvo.put("sourceTaskDefKey", resolveSourceTaskDefKey(ct, rootTaskId));
        tvo.put("targetId", ct.getTargetId());
        tvo.put("targetName", ct.getTargetName());
        tvo.put("targetType", ct.getTargetType());
        tvo.put("status", ct.getStatus());
        tvo.put("content", ct.getContent());
        tvo.put("replyContent", ct.getReplyContent());
        tvo.put("replyUserId", ct.getReplyUserId());
        tvo.put("replyTime", ct.getReplyTime());
        tvo.put("withdrawTime", ct.getWithdrawTime());
        tvo.put("createTime", ct.getCreateTime());
        // 收回必须落到记录所属的会话上：历史会话的记录不能拿当前会话 id 去收回。
        // 权限口径与 withdrawCommunication 一致：发起人可收回任意记录，
        // 上游沟通对象可收回自己转出去的整棵下游子树（含隔级记录）。
        tvo.put("canWithdrawOne", sessionActive
                && canWithdrawRecord(ct, session, currentUserId, recordById));
        return tvo;
    }

    // ===== 待办中心用的只读查询 =====

    /**
     * 按 Camunda 任务ID 查沟通记录（待办中心识别“这是一条沟通待办”后取上下文用）。
     * 任务ID 存在 {@code CAMUNDA_TASK_ID_}；{@code DEPT_PROC_INST_ID_} 已成部门子流程实例ID 专用列。
     */
    public BizCommTask getCommRecordByCamundaTaskId(String camundaTaskId) {
        return getEngineConfig().getCommandExecutorTxRequired().execute(
                (Command<BizCommTask>) commandContext -> commandContext
                        .getSession(BizCommTaskManager.class)
                        .findByCamundaTaskId(camundaTaskId));
    }

    /** 查会话内全部沟通记录（用于展示同一轮其他对象的反馈进度） */
    public List<BizCommTask> getCommRecordsBySessionId(String sessionId) {
        return getEngineConfig().getCommandExecutorTxRequired().execute(
                (Command<List<BizCommTask>>) commandContext -> commandContext
                        .getSession(BizCommTaskManager.class)
                        .findBySessionId(sessionId));
    }

    /**
     * 按来源待办任务批量查沟通记录（跨会话）。
     *
     * <p>流程跟踪图补全「已收回」记录时用：{@link #withdrawCommunication} 收回时会
     * {@code taskService.deleteTask(...)} 删除引擎任务，Camunda 同时把对应的
     * ACT_HI_TASKINST 历史任务删掉，于是只从历史任务取沟通会整条漏掉。
     * 业务表里的记录还在（状态为 WITHDRAWN），按来源待办反查即可还原。</p>
     */
    public List<BizCommTask> getCommRecordsBySourceTaskIds(Collection<String> sourceTaskIds) {
        if (sourceTaskIds == null || sourceTaskIds.isEmpty()) {
            return new ArrayList<>();
        }
        return getEngineConfig().getCommandExecutorTxRequired().execute(
                (Command<List<BizCommTask>>) commandContext -> commandContext
                        .getSession(BizCommTaskManager.class)
                        .findBySourceTaskIds(sourceTaskIds));
    }

    /** 按ID查会话 */
    public BizCommSession getSessionById(String sessionId) {
        return getEngineConfig().getCommandExecutorTxRequired().execute(
                (Command<BizCommSession>) commandContext -> commandContext
                        .getSession(BizCommSessionManager.class)
                        .findById(sessionId));
    }

    /** 按业务记录ID查沟通记录（父链回溯用） */
    public BizCommTask getCommRecordById(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        return getEngineConfig().getCommandExecutorTxRequired().execute(
                (Command<BizCommTask>) commandContext -> commandContext
                        .getSession(BizCommTaskManager.class)
                        .findById(id));
    }

    /**
     * 一条沟通记录真正的“发起人”（把这条沟通发出去的人）。
     *
     * <p>不能拿源待办处理人代替：转交/追问记录挂在父记录下，发出去的人是父记录的
     * 沟通对象（如 Carol 把沟通转给 Erin），而源待办处理人始终是审批人（manager）。
     * 首轮与“补充沟通对象”没有父记录，发起人就是会话发起人。</p>
     */
    public String resolveInitiatorId(BizCommTask record) {
        if (record == null) {
            return null;
        }
        if (record.getParentCommTaskId() != null) {
            BizCommTask parent = getCommRecordById(record.getParentCommTaskId());
            if (parent != null) {
                return parent.getTargetId();
            }
        }
        BizCommSession session = getSessionById(record.getSessionId());
        return session == null ? null : session.getInitiator();
    }

    /**
     * 沟通发起人的显示名。转交/追问取父记录的沟通对象名；
     * 首轮会话只落库了账号，没有显示名，返回 null 由调用方回退到账号。
     */
    public String resolveInitiatorName(BizCommTask record) {
        if (record == null || record.getParentCommTaskId() == null) {
            return null;
        }
        BizCommTask parent = getCommRecordById(record.getParentCommTaskId());
        return parent == null ? null : parent.getTargetName();
    }
}
