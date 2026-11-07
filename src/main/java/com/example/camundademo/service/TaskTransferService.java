package com.example.camundademo.service;

import com.example.camundademo.entity.BizCommTask;
import com.example.camundademo.entity.BizTaskExt;
import com.example.camundademo.entity.BizTaskTransfer;
import com.example.camundademo.persistence.BizTaskExtManager;
import com.example.camundademo.persistence.BizTaskTransferManager;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.task.DelegationState;
import org.camunda.bpm.engine.task.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 待办转办服务。
 *
 * <p>“转办”= 把待办永久交给另一个人办理（改派），与“委派”不同：
 * 委派需要原办理人事后 resolve，转办后原办理人即退出该待办。
 * Camunda 侧只做一件事：{@code setAssignee}。业务侧的转办链单独落
 * {@code BIZ_TASK_TRANSFER_}，因为引擎只保留当前办理人。</p>
 *
 * <p><b>为什么所有校验都放进同一个引擎命令</b>：任务查询、沟通状态判断、改派、落记录
 * 必须在一个事务里完成。先查后改（两次命令）会留下竞态：提交前任务被别人办结，
 * 或者沟通恰好被发起，都会写出与事实不符的转办记录。</p>
 */
@Service
public class TaskTransferService {

    /** REASON_ 列宽 500，超长直接截断，避免落库失败 */
    private static final int MAX_REASON_LENGTH = 500;

    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private TaskService taskService;

    @Autowired
    private IdentityService identityService;

    private ProcessEngineConfigurationImpl getEngineConfig() {
        return (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
    }

    // ===== 写 =====

    /**
     * 转办：仅当前办理人可操作，且不能转给沟通中的待办。
     *
     * @param taskId       待办 ID
     * @param operator     当前登录用户（不可信外部传入）
     * @param targetUserId 接收人
     * @param reason       转办说明，可空
     */
    public Map<String, Object> transfer(String taskId, String operator, String targetUserId, String reason) {
        ProcessEngineConfigurationImpl engineConfig = getEngineConfig();
        return engineConfig.getCommandExecutorTxRequired().execute((Command<Map<String, Object>>) commandContext -> {
            Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
            if (task == null) {
                throw new BusinessException("任务不存在或已被处理");
            }
            String fromUser = task.getAssignee();
            if (fromUser == null || fromUser.isBlank()) {
                // 未签收的候选任务不归属任何人，允许任何人改派等于允许抢单
                throw new BusinessException("该待办尚未分派给具体办理人，无法转办");
            }
            if (!fromUser.equals(operator)) {
                throw new BusinessException("仅当前待办处理人可转办");
            }
            if (BizCommTask.isCommTaskKey(task.getTaskDefinitionKey())) {
                // 沟通任务的办理人是沟通对象本人，改派会让沟通记录里的 targetId 与任务办理人不一致，
                // 反馈权限（仅沟通对象本人可反馈）随即失效。沟通待办请直接反馈或由发起人收回。
                throw new BusinessException("沟通待办不支持转办，请提交反馈或由发起人收回");
            }
            if (DelegationState.PENDING.equals(task.getDelegationState())) {
                // 受托人只对“受托事项”负责，没有处置权。允许转办等于把责任链继续往下踢，
                // 原办理人既不知道转给了谁，也无法追溯。受托人只能提交反馈交回。
                throw new BusinessException("该待办处于委托中，受托人不能转办，请提交反馈或由委托人收回");
            }

            String target = targetUserId == null ? null : targetUserId.trim();
            if (target == null || target.isEmpty()) {
                throw new BusinessException("请选择转办接收人");
            }
            if (target.equals(fromUser)) {
                throw new BusinessException("不能转办给自己");
            }
            if (identityService.createUserQuery().userId(target).count() == 0) {
                throw new BusinessException("接收人不存在：" + target);
            }

            BizTaskExtManager extMgr = commandContext.getSession(BizTaskExtManager.class);
            BizTaskExt ext = extMgr.findByTaskId(taskId);
            if (ext != null && BizTaskExt.STATUS_COMMUNICATING.equals(ext.getBizStatus())) {
                // 与审批锁定同口径：沟通未收口时任务还在流转路径上，
                // 此时改派会让“谁在等沟通结果”变得无法解释，也容易被当成绕过锁定的手段。
                throw new BusinessException("当前待办正在沟通中，请等待沟通完成或收回沟通后再转办");
            }

            String normalizedReason = normalizeReason(reason);

            // 改派 + 同步业务扩展表 + 落转办记录，同一命令内完成
            taskService.setAssignee(taskId, target);
            if (ext != null) {
                ext.setAssignee(target);
                ext.setUpdateTime(new Date());
                extMgr.update(ext);
            }

            BizTaskTransfer record = new BizTaskTransfer();
            record.setId(UUID.randomUUID().toString().replace("-", ""));
            record.setTaskId(taskId);
            record.setProcessInstanceId(task.getProcessInstanceId());
            record.setTaskDefKey(task.getTaskDefinitionKey());
            record.setTaskName(task.getName());
            record.setFromUserId(fromUser);
            record.setToUserId(target);
            record.setReason(normalizedReason);
            record.setCreateTime(new Date());
            commandContext.getSession(BizTaskTransferManager.class).insert(record);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("result", "ok");
            resp.put("transferId", record.getId());
            resp.put("taskId", taskId);
            resp.put("from", fromUser);
            resp.put("to", target);
            return resp;
        });
    }

    private String normalizeReason(String reason) {
        if (reason == null) {
            return null;
        }
        String trimmed = reason.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > MAX_REASON_LENGTH ? trimmed.substring(0, MAX_REASON_LENGTH) : trimmed;
    }

    // ===== 读 =====

    /** 单条待办的转办链（按时间升序） */
    public List<Map<String, Object>> listByTaskId(String taskId) {
        if (taskId == null) {
            return Collections.emptyList();
        }
        ProcessEngineConfigurationImpl engineConfig = getEngineConfig();
        return engineConfig.getCommandExecutorTxRequired().execute((Command<List<Map<String, Object>>>)
                commandContext -> toVOList(commandContext.getSession(BizTaskTransferManager.class)
                        .findByTaskId(taskId)));
    }

    /**
     * 单个流程实例的全部转办记录（按时间升序）。
     *
     * <p>「我的发起」「我的已办」的详情都按实例汇总展示，一个实例里可能有多条待办各转办过。</p>
     */
    public List<Map<String, Object>> listByProcessInstanceId(String processInstanceId) {
        if (processInstanceId == null) {
            return Collections.emptyList();
        }
        ProcessEngineConfigurationImpl engineConfig = getEngineConfig();
        return engineConfig.getCommandExecutorTxRequired().execute((Command<List<Map<String, Object>>>)
                commandContext -> toVOList(commandContext.getSession(BizTaskTransferManager.class)
                        .findByProcessInstanceId(processInstanceId)));
    }

    /**
     * 批量查询，按 taskId 分组。
     *
     * <p>待办列表整页共用一次 IN 查询，避免每行都查一次转办记录。</p>
     */
    public Map<String, List<Map<String, Object>>> groupByTaskIds(Collection<String> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return Collections.emptyMap();
        }
        ProcessEngineConfigurationImpl engineConfig = getEngineConfig();
        return engineConfig.getCommandExecutorTxRequired().execute((Command<Map<String, List<Map<String, Object>>>>)
                commandContext -> {
                    List<BizTaskTransfer> records =
                            commandContext.getSession(BizTaskTransferManager.class).findByTaskIds(taskIds);
                    Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
                    for (BizTaskTransfer record : records) {
                        grouped.computeIfAbsent(record.getTaskId(), k -> new ArrayList<>())
                                .add(toVO(record));
                    }
                    return grouped;
                });
    }

    /** 该用户是否参与过此流程实例的转办（转出或接收） */
    public boolean participatedInTransfer(String processInstanceId, String userId) {
        if (processInstanceId == null || userId == null) {
            return false;
        }
        ProcessEngineConfigurationImpl engineConfig = getEngineConfig();
        return engineConfig.getCommandExecutorTxRequired().execute((Command<Boolean>) commandContext ->
                commandContext.getSession(BizTaskTransferManager.class)
                        .findByProcessInstanceId(processInstanceId).stream()
                        .anyMatch(r -> userId.equals(r.getFromUserId()) || userId.equals(r.getToUserId())));
    }

    private List<Map<String, Object>> toVOList(List<BizTaskTransfer> records) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (BizTaskTransfer record : records) {
            result.add(toVO(record));
        }
        return result;
    }

    private Map<String, Object> toVO(BizTaskTransfer record) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", record.getId());
        vo.put("taskId", record.getTaskId());
        vo.put("taskName", record.getTaskName());
        vo.put("taskDefKey", record.getTaskDefKey());
        vo.put("from", record.getFromUserId());
        vo.put("to", record.getToUserId());
        vo.put("reason", record.getReason());
        vo.put("createTime", record.getCreateTime());
        return vo;
    }
}
