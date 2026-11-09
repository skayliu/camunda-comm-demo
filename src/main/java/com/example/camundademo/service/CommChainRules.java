package com.example.camundademo.service;

import com.example.camundademo.entity.BizCommSession;
import com.example.camundademo.entity.BizCommTask;
import com.example.camundademo.persistence.BizCommTaskManager;
import org.camunda.bpm.engine.task.Task;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 沟通链的纯规则层：父子链遍历、收回权限判定、待反馈后代查找、对象归一化。
 *
 * <p>这些判定被建单（{@link CommTaskFactory}）、状态机（{@link CommStateService}）、
 * 查询（{@link CommQueryService}）三处共用，所以抽成无状态工具，避免三个服务互相引用。
 * 所有方法只依赖入参（需要查库时由调用方在同一个 CommandContext 内传入 Manager），
 * 不在内部开启新的事务。</p>
 */
final class CommChainRules {

    /** 允许的沟通对象类型，与表 BIZ_COMM_TASK_.TARGET_TYPE_ 的取值保持一致 */
    static final Set<String> SUPPORTED_TARGET_TYPES =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("USER", "DEPT")));

    private CommChainRules() {
    }

    /** 沟通对象去重键：类型 + 账号 */
    static String targetKey(String type, String id) {
        return (type == null ? "" : type.trim().toUpperCase(Locale.ROOT)) + ":"
                + (id == null ? "" : id.trim());
    }

    static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.trim().isEmpty()) {
                return c.trim();
            }
        }
        return null;
    }

    static Map<String, BizCommTask> indexById(List<BizCommTask> tasks) {
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

    /** 收集某条记录及其全部后代（含隔级），成环时也不会死循环 */
    static void collectChildren(List<BizCommTask> allTasks, String taskId, Set<String> result) {
        if (!result.add(taskId)) return;
        for (BizCommTask c : allTasks) {
            if (taskId.equals(c.getParentCommTaskId())) {
                collectChildren(allTasks, c.getId(), result);
            }
        }
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
    static boolean isUpstreamOf(BizCommTask record, Map<String, BizCommTask> byId, String operator) {
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
     * 与 {@link CommStateService#withdrawCommunication} 的校验保持同一口径，前端按钮据此显示。
     *
     * <p>注意上游收回的<b>执行范围</b>是整个下游链：按钮虽然挂在某一条记录上，
     * 点下去会把自己下游全部未反馈记录一起取消（A→B、B→C/D、C→E 时 B 收回即取消 C、D、E）。</p>
     */
    static boolean canWithdrawRecord(BizCommTask record, BizCommSession session, String operator,
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
     * 找出该沟通记录下（任意层级）仍在等待反馈的子沟通。
     *
     * <p>父子关系来自 {@code PARENT_COMM_TASK_ID_}：沟通对象可以把问题转给他人，
     * 被转交人还能继续往下转，因此这里按广度优先遍历整棵子树，而不是只看直接子记录。</p>
     *
     * @return 第一条仍未反馈的后代记录；没有则返回 null
     */
    static BizCommTask findPendingDescendant(String recordId, List<BizCommTask> allTasks) {
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
    static String commTargetLabel(BizCommTask record) {
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
    static Set<String> collectUpstreamTargetIds(BizCommTask record, BizCommTaskManager taskMgr) {
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
    static String resolveTaskDisplayName(Task sourceTask) {
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
     * 归一化沟通对象。
     *
     * <p>接口文档约定用 {@code {"type","id","name"}}，但前端人员选择器常直接提交
     * {@code {"type","name","value"}}（name 是账号、value 是显示名）。两种格式都在这里收敛：
     * 账号标识按 id → userId → targetId → name → value 取第一个非空值，
     * 显示名按 displayName → targetName → value → name 取第一个非空值。</p>
     *
     * <p>解析不出账号时抛业务异常，而不是让 null 落到 TARGET_ID_（NOT NULL）上引发 500。</p>
     */
    static List<Map<String, String>> normalizeTargets(List<Map<String, String>> rawTargets) {
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

            Map<String, String> normalized = new HashMap<>(6);
            normalized.put("type", type);
            normalized.put("id", targetId);
            normalized.put("name", targetName);
            // 部门沟通可指定注册表里的子流程 key（通用/部门专属）；
            // 人员沟通忽略这个字段，带上来也无害。
            String subProcessKey = firstNonBlank(raw.get("subProcessKey"), raw.get("processKey"));
            if (subProcessKey != null) {
                normalized.put("subProcessKey", subProcessKey);
            }
            result.add(normalized);
        }
        return result;
    }
}
