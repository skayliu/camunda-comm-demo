package com.example.camundademo.service;

import com.example.camundademo.entity.BizCommSession;
import com.example.camundademo.entity.BizCommTask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 旁路沟通门面。
 *
 * <p>原来 1200 余行的实现按职责拆成三个服务，这里只保留对外签名与委派：</p>
 * <ul>
 *   <li>{@link CommTaskFactory} —— 建单：发起、补充对象、追问、转交；</li>
 *   <li>{@link CommStateService} —— 状态机：收回、反馈；</li>
 *   <li>{@link CommQueryService} —— 查询：沟通状态聚合、会话/记录只读查询。</li>
 * </ul>
 *
 * <p>三者共用的父子链遍历与权限判定收在 {@link CommChainRules}，是一个无状态工具类，
 * 因此三个服务之间没有相互依赖，门面也不承载任何业务规则。
 * Controller、WorkbenchService、ProcessTraceService 与测试的调用方式保持不变。</p>
 */
@Service
public class CommunicationService {

    @Autowired
    private CommTaskFactory commTaskFactory;

    @Autowired
    private CommStateService commStateService;

    @Autowired
    private CommQueryService commQueryService;

    // ===== 建单 =====

    /** 发起沟通（兼容旧签名：只返回会话ID） */
    public String startCommunication(String rootTaskId, String initiator, String content,
                                     List<Map<String, String>> targets) {
        return commTaskFactory.startCommunication(rootTaskId, initiator, content, targets);
    }

    /** 发起沟通，并返回本次操作的明细（是否追加、新增/跳过的人员） */
    public Map<String, Object> startCommunicationDetailed(String rootTaskId, String initiator, String content,
                                                          List<Map<String, String>> targets) {
        return commTaskFactory.startCommunicationDetailed(rootTaskId, initiator, content, targets);
    }

    /** 继续沟通（原发起人追问） */
    public void continueCommunication(String parentCommTaskRecordId, String userId, String content,
                                      List<Map<String, String>> targets) {
        commTaskFactory.continueCommunication(parentCommTaskRecordId, userId, content, targets);
    }

    /** 沟通对象把当前沟通转给其他人补充核实 */
    public Map<String, Object> forwardCommunication(String commRecordId, String operator, String content,
                                                    List<Map<String, String>> targets) {
        return commTaskFactory.forwardCommunication(commRecordId, operator, content, targets);
    }

    // ===== 状态机 =====

    /** 收回沟通（部分或全部），返回实际被收回的记录条数 */
    public int withdrawCommunication(String sessionId, String operator, List<String> commTaskIds) {
        return commStateService.withdrawCommunication(sessionId, operator, commTaskIds);
    }

    /** 批量计算会话内每条记录当前用户能否收回 */
    public Map<String, Boolean> withdrawableRecordMap(String sessionId, String operator) {
        return commStateService.withdrawableRecordMap(sessionId, operator);
    }

    /** 反馈沟通意见 */
    public void replyCommTask(String commTaskRecordId, String userId, String replyContent) {
        commStateService.replyCommTask(commTaskRecordId, userId, replyContent);
    }

    // ===== 查询 =====

    /** 查询待办沟通状态（含全部历史会话与可操作标记） */
    public Map<String, Object> getTaskCommStatus(String taskId, String currentUserId) {
        return commQueryService.getTaskCommStatus(taskId, currentUserId);
    }

    /** 按 Camunda 任务ID 查沟通记录 */
    public BizCommTask getCommRecordByCamundaTaskId(String camundaTaskId) {
        return commQueryService.getCommRecordByCamundaTaskId(camundaTaskId);
    }

    /** 查会话内全部沟通记录 */
    public List<BizCommTask> getCommRecordsBySessionId(String sessionId) {
        return commQueryService.getCommRecordsBySessionId(sessionId);
    }

    /** 按来源待办任务批量查沟通记录（跨会话） */
    public List<BizCommTask> getCommRecordsBySourceTaskIds(Collection<String> sourceTaskIds) {
        return commQueryService.getCommRecordsBySourceTaskIds(sourceTaskIds);
    }

    /** 按ID查会话 */
    public BizCommSession getSessionById(String sessionId) {
        return commQueryService.getSessionById(sessionId);
    }

    /** 按业务记录ID查沟通记录 */
    public BizCommTask getCommRecordById(String id) {
        return commQueryService.getCommRecordById(id);
    }

    /** 一条沟通记录真正的发起人账号 */
    public String resolveInitiatorId(BizCommTask record) {
        return commQueryService.resolveInitiatorId(record);
    }

    /** 一条沟通记录真正的发起人显示名 */
    public String resolveInitiatorName(BizCommTask record) {
        return commQueryService.resolveInitiatorName(record);
    }
}
