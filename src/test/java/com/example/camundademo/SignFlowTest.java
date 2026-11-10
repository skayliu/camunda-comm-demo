package com.example.camundademo;

import com.example.camundademo.entity.BizSignRecord;
import com.example.camundademo.service.BusinessException;
import com.example.camundademo.service.CommunicationLockedException;
import com.example.camundademo.service.SignService;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 加签端到端集成测试：前加签、后加签的通过 / 驳回 / 减签补人 / 锁粒度，以及并加签扩容。
 *
 * <p>钉住的都是“做错了不会报错、只会悄悄走错”的点：</p>
 * <ul>
 *   <li>前加签必须真的锁住原任务（用户点审批要被拦住），轮次通过后必须解锁；</li>
 *   <li>任一驳回即整轮不通过：其余加签人待办要被引擎销毁，记录不能停在 PENDING；</li>
 *   <li>后加签期间原任务不能提前 complete，轮次结果要与原意见合并后再提交；</li>
 *   <li>多人并行/串行由发起人选，落到启动哪个子流程定义；</li>
 *   <li>多实例节点上的前加签只锁发起人自己那一张任务，同节点他人不受影响；</li>
 *   <li>减签只允许减未提交意见的人，减完可以补人重加，轮次不推翻。</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:signflowdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class SignFlowTest {

    /** 多实例主流程：用于验证锁粒度与并加签扩容（approval 是单实例，不能覆盖这两条） */
    private static final String MULTI_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              id="Definitions_sign_mi" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="signMultiInstanceTest" name="多实例加签测试" isExecutable="true"
                            camunda:historyTimeToLive="180">
                <bpmn:startEvent id="start">
                  <bpmn:outgoing>f1</bpmn:outgoing>
                </bpmn:startEvent>
                <bpmn:userTask id="miReview" name="并行会签" camunda:assignee="${assignee}">
                  <bpmn:incoming>f1</bpmn:incoming>
                  <bpmn:outgoing>f2</bpmn:outgoing>
                  <bpmn:multiInstanceLoopCharacteristics isSequential="false"
                                                        camunda:collection="${assigneeList}"
                                                        camunda:elementVariable="assignee"/>
                </bpmn:userTask>
                <bpmn:endEvent id="end">
                  <bpmn:incoming>f2</bpmn:incoming>
                </bpmn:endEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="miReview"/>
                <bpmn:sequenceFlow id="f2" sourceRef="miReview" targetRef="end"/>
              </bpmn:process>
            </bpmn:definitions>
            """;

    private static boolean multiDeployed = false;

    @Autowired
    private SignService signService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private IdentityService identityService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void deployMultiInstanceProcess() {
        if (multiDeployed) {
            return;
        }
        repositoryService.createDeployment()
                .addString("sign-multi-instance-test.bpmn", MULTI_BPMN)
                .name("sign-multi-instance-test")
                .deploy();
        multiDeployed = true;
    }

    // ==================================================================
    // 前加签
    // ==================================================================

    @Test
    void preSignWithTwoUsersUnlocksOriginalTaskAfterAllApproved() {
        ProcessInstance pi = startApproval();
        Task managerTask = startToManagerTask(pi);

        Map<String, Object> resp = signService.add(managerTask.getId(), "manager", "PRE",
                List.of("bob", "carol"), false);

        assertEquals("PRE", resp.get("signType"));
        assertEquals("PARALLEL", resp.get("signMode"), "发起人选择并行，必须落到并行子流程");
        assertEquals(List.of("bob", "carol"), resp.get("addedUsers"));

        // 原任务被锁：用户点审批必须被拦住，而不是悄悄跳节点
        assertEquals("WAIT_PRE_SIGN", bizStatus(managerTask.getId()));
        assertThrows(CommunicationLockedException.class,
                () -> taskService.complete(managerTask.getId()),
                "前加签进行中，原任务必须被拦截");

        List<Map<String, Object>> records = signService.listByTaskId(managerTask.getId());
        assertEquals(2, records.size());
        String roundId = String.valueOf(records.get(0).get("signRoundId"));
        assertEquals(roundId, records.get(1).get("signRoundId"), "多人必须归在同一轮");
        assertEquals("PARALLEL", records.get(0).get("signMode"));
        String subId = String.valueOf(records.get(0).get("subProcessInstanceId"));
        assertNotNull(subId, "SUB_PROC_INST_ID_ 必须随插入落库，否则轮次结束找不到记录");
        assertEquals(subId, records.get(1).get("subProcessInstanceId"));

        // 并行：两人同时有待办
        Task bobTask = addSignTaskOf(subId, "bob");
        Task carolTask = addSignTaskOf(subId, "carol");
        assertNotNull(bobTask, "并行加签应立即出 bob 的待办");
        assertNotNull(carolTask, "并行加签应立即出 carol 的待办");

        signService.completeAddSignTask(bobTask.getId(), "bob", true, "同意");
        assertEquals("WAIT_PRE_SIGN", bizStatus(managerTask.getId()),
                "ALL 模式下只完成一人时不能解锁");

        signService.completeAddSignTask(carolTask.getId(), "carol", true, "同意");

        // 轮次收口：解锁原任务，子流程结束
        assertEquals("NORMAL", bizStatus(managerTask.getId()), "轮次通过后必须解锁原任务");
        assertNotNull(taskOf(pi.getId(), "manager_review"), "解锁后原任务仍在原地等待本人审批");
        assertEquals(0, runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(pi.getId() + ":" + roundId).count(), "子轮次应已结束");

        List<Map<String, Object>> done = signService.listByTaskId(managerTask.getId());
        assertEquals("APPROVED", recordOf(done, "bob").get("status"));
        assertEquals("APPROVED", recordOf(done, "carol").get("status"));
        assertEquals(roundId, recordOf(done, "bob").get("signRoundId"));

        // 解锁后本人可以正常办结
        signService.completeMainTask(managerTask.getId(), "manager", true, "经理同意");
        assertNotNull(taskOf(pi.getId(), "finance_review"), "解锁后办结应正常流转到财务");
    }

    @Test
    void preSignAnyRejectionFailsWholeRound() {
        ProcessInstance pi = startApproval();
        Task managerTask = startToManagerTask(pi);

        signService.add(managerTask.getId(), "manager", "PRE", List.of("bob", "carol"), false);
        List<Map<String, Object>> records = signService.listByTaskId(managerTask.getId());
        String subId = String.valueOf(records.get(0).get("subProcessInstanceId"));

        Task bobTask = addSignTaskOf(subId, "bob");
        signService.completeAddSignTask(bobTask.getId(), "bob", false, "资料不全");

        // 任一驳回：整轮不通过，原任务被 complete(approved=false)，不再回到本人手里
        assertNull(taskOf(pi.getId(), "manager_review"), "前加签驳回后原任务不应继续等待");
        assertNotNull(taskOf(pi.getId(), "finance_review"), "approval 无驳回网关，按拒绝结果继续流转");
        assertEquals(Boolean.FALSE, runtimeService.getVariable(pi.getId(), "approved"));
        assertEquals(Boolean.TRUE, runtimeService.getVariable(pi.getId(), "signRejected"));

        // 其余加签人待办被引擎销毁，记录不能停在 PENDING
        assertNull(addSignTaskOf(subId, "carol"), "驳回后其余加签人待办必须撤销");
        List<Map<String, Object>> done = signService.listByTaskId(managerTask.getId());
        assertEquals("REJECTED", recordOf(done, "bob").get("status"));
        assertEquals("CANCELLED", recordOf(done, "carol").get("status"));
        assertEquals("资料不全", recordOf(done, "bob").get("opinion"));
    }

    @Test
    void preSignRemoveThenReAddKeepsRoundOpen() {
        ProcessInstance pi = startApproval();
        Task managerTask = startToManagerTask(pi);

        signService.add(managerTask.getId(), "manager", "PRE", List.of("bob", "carol"), false);
        List<Map<String, Object>> records = signService.listByTaskId(managerTask.getId());
        String roundId = String.valueOf(records.get(0).get("signRoundId"));
        String subId = String.valueOf(records.get(0).get("subProcessInstanceId"));
        String carolRecordId = String.valueOf(recordOf(records, "carol").get("id"));

        // 减掉加错的 carol：任务消失、记录取消，轮次继续
        signService.remove(carolRecordId, "manager");
        assertNull(addSignTaskOf(subId, "carol"), "减签后该人的加签待办必须消失");
        assertNotNull(addSignTaskOf(subId, "bob"), "减签不能连带撤销其他人");
        assertEquals("CANCELLED", recordOf(signService.listByTaskId(managerTask.getId()), "carol").get("status"));
        assertEquals("WAIT_PRE_SIGN", bizStatus(managerTask.getId()));

        // 补人重加：沿用同一轮，不新建 roundId
        signService.add(managerTask.getId(), "manager", "PRE", List.of("dave"), false);
        List<Map<String, Object>> after = signService.listByTaskId(managerTask.getId());
        assertEquals(3, after.size());
        assertEquals(roundId, recordOf(after, "dave").get("signRoundId"), "补人必须留在原轮次");
        assertEquals("PENDING", recordOf(after, "dave").get("status"));
        assertNotNull(addSignTaskOf(subId, "dave"), "补人后应立刻出现新待办");

        // 已提交意见的人不可减
        Task bobTask = addSignTaskOf(subId, "bob");
        signService.completeAddSignTask(bobTask.getId(), "bob", true, "同意");
        String bobRecordId = String.valueOf(recordOf(after, "bob").get("id"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> signService.remove(bobRecordId, "manager"));
        assertTrue(ex.getMessage().contains("已提交意见"), "已提交意见只能走驳回，不能被减签");

        // dave 同意后 BLOCK：bob 已完成，dave 完成即满足 ALL
        Task daveTask = addSignTaskOf(subId, "dave");
        signService.completeAddSignTask(daveTask.getId(), "dave", true, "同意");
        assertEquals("NORMAL", bizStatus(managerTask.getId()));
        assertNotNull(taskOf(pi.getId(), "manager_review"));
    }

    @Test
    void preSignOnMultiInstanceNodeOnlyLocksInitiatorTask() {
        ProcessInstance pi = startMulti(List.of("alice", "bob"));
        Task aliceTask = miTask(pi.getId(), "alice");
        Task bobTask = miTask(pi.getId(), "bob");
        assertNotNull(aliceTask);
        assertNotNull(bobTask);

        signService.add(aliceTask.getId(), "alice", "PRE", List.of("carol"), false);

        assertEquals("WAIT_PRE_SIGN", bizStatus(aliceTask.getId()));
        // 任务创建监听器会给每张待办写入 NORMAL，因此「未被锁」的判据是仍为 NORMAL，
        // 而不是扩展表没有记录；这里同时确认不会产生扩展开记录
        assertEquals("NORMAL", bizStatus(bobTask.getId()),
                "同节点其他人的任务不应被锁");
        assertTrue(signService.listByTaskId(bobTask.getId()).isEmpty(),
                "同节点其他人的任务不应产生加签记录");

        // bob 不受影响，能正常办结；节点因 alice 仍在而未收敛
        taskService.complete(bobTask.getId());
        assertNotNull(miTask(pi.getId(), "alice"), "前加签进行中，发起人任务必须留在节点内");
        assertEquals(1, runtimeService.createProcessInstanceQuery()
                .processInstanceId(pi.getId()).count(), "流程实例仍应存活");

        List<Map<String, Object>> records = signService.listByTaskId(aliceTask.getId());
        String subId = String.valueOf(records.get(0).get("subProcessInstanceId"));
        Task carolTask = addSignTaskOf(subId, "carol");
        signService.completeAddSignTask(carolTask.getId(), "carol", true, "同意");

        assertEquals("NORMAL", bizStatus(aliceTask.getId()), "只解锁发起人自己那一张");
        signService.completeMainTask(aliceTask.getId(), "alice", true, "同意");
        assertNull(taskOf(pi.getId(), "miReview"), "两人都完成后节点应收敛");
    }

    // ==================================================================
    // 后加签
    // ==================================================================

    @Test
    void postSignSequentialRunsOneByOneThenCompletesOriginalTask() {
        ProcessInstance pi = startApproval();
        Task managerTask = startToManagerTask(pi);

        Map<String, Object> resp = signService.completeWithPostSign(managerTask.getId(), "manager",
                "同意，请复核", true, List.of("bob", "carol"), true);

        assertEquals(Boolean.TRUE, resp.get("postSignStarted"));
        assertEquals("WAIT_POST_SIGN", bizStatus(managerTask.getId()));
        // 后加签期间不能提前把原任务办结，否则后置意见会被丢掉
        assertThrows(CommunicationLockedException.class,
                () -> taskService.complete(managerTask.getId()));

        List<Map<String, Object>> records = signService.listByTaskId(managerTask.getId());
        assertEquals(2, records.size());
        assertEquals("POST", records.get(0).get("signType"));
        assertEquals("SEQUENTIAL", records.get(0).get("signMode"), "发起人选择串行，必须落到串行子流程");
        assertEquals("同意，请复核", recordOf(records, "bob").get("opinion"),
                "原意见要随轮次暂存，收口时才能合并");
        assertEquals(Integer.valueOf(1), recordOf(records, "bob").get("sequenceNo"));
        assertEquals(Integer.valueOf(2), recordOf(records, "carol").get("sequenceNo"));

        String subId = String.valueOf(records.get(0).get("subProcessInstanceId"));
        Task bobTask = addSignTaskOf(subId, "bob");
        assertNotNull(bobTask, "串行时只应出现第一个人的待办");
        assertNull(addSignTaskOf(subId, "carol"), "串行时第二个人不应提前出待办");

        signService.completeAddSignTask(bobTask.getId(), "bob", true, "复核通过");
        Task carolTask = addSignTaskOf(subId, "carol");
        assertNotNull(carolTask, "第一人完成后应轮到第二人");

        signService.completeAddSignTask(carolTask.getId(), "carol", true, "也通过");

        // 收口：原意见同意 + 轮次通过 → 原任务真正 complete
        assertNull(taskOf(pi.getId(), "manager_review"), "后加签收口后原任务应被办结");
        assertNotNull(taskOf(pi.getId(), "finance_review"));
        assertEquals(Boolean.TRUE, runtimeService.getVariable(pi.getId(), "approved"));
        assertEquals(Boolean.FALSE, runtimeService.getVariable(pi.getId(), "signRejected"));

        List<Map<String, Object>> done = signService.listByTaskId(managerTask.getId());
        assertEquals("APPROVED", recordOf(done, "bob").get("status"));
        assertEquals("APPROVED", recordOf(done, "carol").get("status"));
    }

    @Test
    void postSignParallelKeepsWaitingAfterFirstApprovalThenCompletesOnAllApproved() {
        ProcessInstance pi = startApproval();
        Task managerTask = startToManagerTask(pi);

        Map<String, Object> resp = signService.completeWithPostSign(managerTask.getId(), "manager",
                "同意，请复核", true, List.of("bob", "carol"), false);
        assertEquals(Boolean.TRUE, resp.get("postSignStarted"));

        List<Map<String, Object>> records = signService.listByTaskId(managerTask.getId());
        assertEquals(2, records.size());
        assertEquals("PARALLEL", records.get(0).get("signMode"),
                "发起人没选串行，必须落到并行子流程，不能悄悄退化成串行");
        assertEquals(records.get(0).get("signRoundId"), records.get(1).get("signRoundId"),
                "多人必须归在同一轮");

        String subId = String.valueOf(records.get(0).get("subProcessInstanceId"));
        Task bobTask = addSignTaskOf(subId, "bob");
        Task carolTask = addSignTaskOf(subId, "carol");
        assertNotNull(bobTask, "并行后加签应同时出 bob 的待办");
        assertNotNull(carolTask, "并行后加签应同时出 carol 的待办");

        signService.completeAddSignTask(bobTask.getId(), "bob", true, "我复核通过");

        // ALL 模式下只完成一人：原任务仍被锁，另一人的待办不能被提前销毁
        assertEquals("WAIT_POST_SIGN", bizStatus(managerTask.getId()),
                "ALL 模式下只完成一人时，原任务不能提前办结");
        assertNotNull(taskOf(pi.getId(), "manager_review"), "原任务必须仍在原地等待后加签收口");
        assertThrows(CommunicationLockedException.class,
                () -> taskService.complete(managerTask.getId()));
        assertNotNull(addSignTaskOf(subId, "carol"), "并行模式下一人通过不能撤销他人待办");

        signService.completeAddSignTask(carolTask.getId(), "carol", true, "也通过");

        assertNull(taskOf(pi.getId(), "manager_review"), "最后一人通过后原任务应被办结");
        assertNotNull(taskOf(pi.getId(), "finance_review"));
        assertEquals(Boolean.TRUE, runtimeService.getVariable(pi.getId(), "approved"));
        assertEquals(Boolean.FALSE, runtimeService.getVariable(pi.getId(), "signRejected"),
                "全员通过不应写驳回标记");

        List<Map<String, Object>> done = signService.listByTaskId(managerTask.getId());
        assertEquals("APPROVED", recordOf(done, "bob").get("status"));
        assertEquals("APPROVED", recordOf(done, "carol").get("status"));
    }

    @Test
    void postSignRoundRejectionRejectsOriginalTask() {
        ProcessInstance pi = startApproval();
        Task managerTask = startToManagerTask(pi);

        signService.completeWithPostSign(managerTask.getId(), "manager",
                "我同意，请复核", true, List.of("bob"), false);
        List<Map<String, Object>> records = signService.listByTaskId(managerTask.getId());
        String subId = String.valueOf(records.get(0).get("subProcessInstanceId"));

        Task bobTask = addSignTaskOf(subId, "bob");
        signService.completeAddSignTask(bobTask.getId(), "bob", false, "不同意");

        assertNull(taskOf(pi.getId(), "manager_review"), "轮次驳回后原任务按拒绝收口");
        assertNotNull(taskOf(pi.getId(), "finance_review"));
        assertEquals(Boolean.FALSE, runtimeService.getVariable(pi.getId(), "approved"));
        assertEquals("REJECTED", recordOf(signService.listByTaskId(managerTask.getId()), "bob").get("status"));
    }

    @Test
    void postSignIsNotStartedWhenOriginalApproverRejects() {
        ProcessInstance pi = startApproval();
        Task managerTask = startToManagerTask(pi);

        Map<String, Object> resp = signService.completeWithPostSign(managerTask.getId(), "manager",
                "不同意", false, List.of("bob"), false);

        assertEquals(Boolean.FALSE, resp.get("postSignStarted"),
                "原审批人拒绝时不应启动后加签（避免“先拒绝再请人复核”的矛盾节点）");
        assertNull(taskOf(pi.getId(), "manager_review"));
        assertNotNull(taskOf(pi.getId(), "finance_review"));
        assertEquals(Boolean.FALSE, runtimeService.getVariable(pi.getId(), "approved"));
        assertTrue(signService.listByTaskId(managerTask.getId()).isEmpty(),
                "未启动轮次就不应留下加签记录");
    }

    @Test
    void postSignRemoveThenReAddKeepsRoundOpen() {
        ProcessInstance pi = startApproval();
        Task managerTask = startToManagerTask(pi);

        signService.completeWithPostSign(managerTask.getId(), "manager",
                "同意，请复核", true, List.of("bob", "carol"), false);

        List<Map<String, Object>> records = signService.listByTaskId(managerTask.getId());
        String roundId = String.valueOf(records.get(0).get("signRoundId"));
        String subId = String.valueOf(records.get(0).get("subProcessInstanceId"));
        String carolRecordId = String.valueOf(recordOf(records, "carol").get("id"));

        // 减掉加错的 carol：只取消她那一张，轮次与原任务仍处于后加签等待态
        signService.remove(carolRecordId, "manager");
        assertNull(addSignTaskOf(subId, "carol"), "减签后该人的后加签待办必须消失");
        assertNotNull(addSignTaskOf(subId, "bob"), "减签不能连带撤销其他加签人");
        assertEquals("WAIT_POST_SIGN", bizStatus(managerTask.getId()),
                "减签不结束轮次，原任务仍不能提前提交");

        // 补人：沿用同一轮，不新建 roundId，也不要求重新提交审批
        signService.add(managerTask.getId(), "manager", "POST", List.of("dave"), false);
        List<Map<String, Object>> after = signService.listByTaskId(managerTask.getId());
        assertEquals(3, after.size());
        assertEquals(roundId, recordOf(after, "dave").get("signRoundId"), "补人必须留在原轮次");
        assertEquals("PENDING", recordOf(after, "dave").get("status"));
        assertNotNull(addSignTaskOf(subId, "dave"), "补人后应立刻出现新待办");

        signService.completeAddSignTask(addSignTaskOf(subId, "bob").getId(), "bob", true, "同意");
        signService.completeAddSignTask(addSignTaskOf(subId, "dave").getId(), "dave", true, "同意");

        // 收口：原意见同意 + 轮次通过 → 原任务真正办结
        assertNull(taskOf(pi.getId(), "manager_review"), "补人后轮次通过，原任务应被办结");
        assertNotNull(taskOf(pi.getId(), "finance_review"));
        assertEquals(Boolean.TRUE, runtimeService.getVariable(pi.getId(), "approved"));
        assertEquals("CANCELLED",
                recordOf(signService.listByTaskId(managerTask.getId()), "carol").get("status"));
    }

    // ==================================================================
    // 并加签
    // ==================================================================

    @Test
    void parallelSignOnSingleInstanceNodeIsRejectedWithActionableMessage() {
        ProcessInstance pi = startApproval();
        Task managerTask = startToManagerTask(pi);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> signService.add(managerTask.getId(), "manager", "PARALLEL", List.of("bob"), false));
        assertTrue(ex.getMessage().contains("不是多实例"), "必须提示改成多实例或改用前/后加签");
        assertTrue(ex.getMessage().contains("前加签") || ex.getMessage().contains("后加签"),
                "报错要给出可执行的替代方案");
        assertEquals(0, signService.listByTaskId(managerTask.getId()).size(), "被拒绝时不能留脏记录");
    }

    @Test
    void parallelSignExpandsMultiInstanceAndSharesOneRound() {
        ProcessInstance pi = startMulti(List.of("alice", "bob"));
        Task aliceTask = miTask(pi.getId(), "alice");

        signService.add(aliceTask.getId(), "alice", "PARALLEL", List.of("carol", "dave"), false);
        List<Map<String, Object>> records = signService.listByTaskId(aliceTask.getId());
        assertEquals(2, records.size());
        String roundId = String.valueOf(records.get(0).get("signRoundId"));
        assertEquals(roundId, records.get(1).get("signRoundId"), "同一源任务的并加签必须在同一轮");
        assertNotNull(miTask(pi.getId(), "carol"), "并加签应在节点内扩容出新待办");
        assertNotNull(miTask(pi.getId(), "dave"));

        // 再补一个人：不能新建轮次，否则驳回只撤掉一半
        signService.add(aliceTask.getId(), "alice", "PARALLEL", List.of("erin"), false);
        List<Map<String, Object>> after = signService.listByTaskId(aliceTask.getId());
        assertEquals(3, after.size());
        assertEquals(roundId, recordOf(after, "erin").get("signRoundId"));
        assertNotEquals(null, miTask(pi.getId(), "erin"));

        // 加签人驳回：整轮不通过，其余加签人待办撤销；同节点其他原审批人不受影响
        Task carolTask = miTask(pi.getId(), "carol");
        signService.completeMainTask(carolTask.getId(), "carol", false, "不同意");

        assertNull(miTask(pi.getId(), "dave"), "同一轮内其余加签人待办必须撤销");
        assertNull(miTask(pi.getId(), "erin"), "后来补进来的人也要一起撤销");
        assertNotNull(miTask(pi.getId(), "alice"), "原审批人不是这一轮加签人，不应被撤销");
        assertNotNull(miTask(pi.getId(), "bob"));

        List<Map<String, Object>> done = signService.listByTaskId(aliceTask.getId());
        assertEquals("REJECTED", recordOf(done, "carol").get("status"));
        assertEquals("CANCELLED", recordOf(done, "dave").get("status"));
        assertEquals("CANCELLED", recordOf(done, "erin").get("status"));
        assertEquals(1, done.stream().map(r -> r.get("signRoundId")).distinct().count(),
                "整批并加签只能有一个 roundId");
    }

    @Test
    void parallelSignRemoveThenReAddKeepsMultiInstanceNodeAlive() {
        ProcessInstance pi = startMulti(List.of("alice", "bob"));
        Task aliceTask = miTask(pi.getId(), "alice");

        signService.add(aliceTask.getId(), "alice", "PARALLEL", List.of("carol", "dave"), false);
        List<Map<String, Object>> records = signService.listByTaskId(aliceTask.getId());
        String roundId = String.valueOf(records.get(0).get("signRoundId"));
        String carolRecordId = String.valueOf(recordOf(records, "carol").get("id"));

        // 减签：并加签走 cancelActivityInstance，多实例计数必须跟着减，否则节点会等一个已消失的实例
        signService.remove(carolRecordId, "alice");
        assertNull(miTask(pi.getId(), "carol"), "减签后并加签待办必须消失");
        assertNotNull(miTask(pi.getId(), "dave"), "减签不能连带撤销其他并加签人");
        assertEquals("CANCELLED",
                recordOf(signService.listByTaskId(aliceTask.getId()), "carol").get("status"));

        // 补人：沿用原轮次
        signService.add(aliceTask.getId(), "alice", "PARALLEL", List.of("erin"), false);
        List<Map<String, Object>> after = signService.listByTaskId(aliceTask.getId());
        assertEquals(roundId, recordOf(after, "erin").get("signRoundId"), "补人必须留在原轮次");
        assertNotNull(miTask(pi.getId(), "erin"), "补人后应立刻出现新的并加签待办");

        // 剩余的人全部完成后节点必须收敛，这是减签最容易出的事故：卡在 nrOfInstances
        signService.completeMainTask(miTask(pi.getId(), "dave").getId(), "dave", true, "同意");
        signService.completeMainTask(miTask(pi.getId(), "erin").getId(), "erin", true, "同意");
        signService.completeMainTask(aliceTask.getId(), "alice", true, "同意");
        taskService.complete(miTask(pi.getId(), "bob").getId());

        assertNull(taskOf(pi.getId(), "miReview"), "减签 + 补人后节点必须收敛，不能卡死");
    }

    // ==================================================================
    // 前加签 · 串行
    // ==================================================================

    @Test
    void preSignSequentialRunsOneByOne() {
        ProcessInstance pi = startApproval();
        Task managerTask = startToManagerTask(pi);

        Map<String, Object> resp = signService.add(managerTask.getId(), "manager", "PRE",
                List.of("bob", "carol"), true);
        assertEquals("SEQUENTIAL", resp.get("signMode"), "发起人选择串行，必须落到串行子流程");

        List<Map<String, Object>> records = signService.listByTaskId(managerTask.getId());
        String subId = String.valueOf(records.get(0).get("subProcessInstanceId"));
        assertEquals(Integer.valueOf(1), recordOf(records, "bob").get("sequenceNo"));
        assertEquals(Integer.valueOf(2), recordOf(records, "carol").get("sequenceNo"));

        assertNotNull(addSignTaskOf(subId, "bob"), "串行时只应出现第一个人的待办");
        assertNull(addSignTaskOf(subId, "carol"), "串行时第二个人不应提前出待办");

        signService.completeAddSignTask(addSignTaskOf(subId, "bob").getId(), "bob", true, "先审");
        assertEquals("WAIT_PRE_SIGN", bizStatus(managerTask.getId()),
                "串行只完成一人时整轮未结束，原任务不能解锁");
        assertNotNull(addSignTaskOf(subId, "carol"), "第一人完成后应轮到第二人");

        signService.completeAddSignTask(addSignTaskOf(subId, "carol").getId(), "carol", true, "再审");
        assertEquals("NORMAL", bizStatus(managerTask.getId()), "串行全部通过后必须解锁原任务");
        assertNotNull(taskOf(pi.getId(), "manager_review"));
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private ProcessInstance startApproval() {
        identityService.setAuthenticatedUserId("applicant");
        try {
            return runtimeService.startProcessInstanceByKey("approval", Map.of("applicant", "applicant"));
        } finally {
            identityService.clearAuthentication();
        }
    }

    private ProcessInstance startMulti(List<String> assignees) {
        identityService.setAuthenticatedUserId("applicant");
        try {
            return runtimeService.startProcessInstanceByKey("signMultiInstanceTest",
                    Map.of("assigneeList", assignees));
        } finally {
            identityService.clearAuthentication();
        }
    }

    private Task startToManagerTask(ProcessInstance pi) {
        Task apply = taskOf(pi.getId(), "apply");
        assertNotNull(apply, "流程未停在发起节点");
        taskService.complete(apply.getId());
        Task managerTask = taskOf(pi.getId(), "manager_review");
        assertNotNull(managerTask, "流程未进入经理审批节点");
        return managerTask;
    }

    private Task taskOf(String processInstanceId, String taskDefinitionKey) {
        return taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey(taskDefinitionKey)
                .list().stream().findFirst().orElse(null);
    }

    private Task miTask(String processInstanceId, String assignee) {
        return taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskDefinitionKey("miReview")
                .taskAssignee(assignee)
                .list().stream().findFirst().orElse(null);
    }

    private Task addSignTaskOf(String subProcessInstanceId, String assignee) {
        return taskService.createTaskQuery()
                .processInstanceId(subProcessInstanceId)
                .taskDefinitionKey(BizSignRecord.ADD_SIGN_TASK_KEY)
                .taskAssignee(assignee)
                .list().stream().findFirst().orElse(null);
    }

    /** 直接读扩展表：bizStatus 没有对外开放的读接口，测试要从库上看锁到底是真有还是假有 */
    private String bizStatus(String taskId) {
        List<String> statuses = jdbcTemplate.queryForList(
                "SELECT BIZ_STATUS_ FROM BIZ_TASK_EXT_ WHERE TASK_ID_ = ?", String.class, taskId);
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    private Map<String, Object> recordOf(List<Map<String, Object>> records, String addUserId) {
        for (Map<String, Object> record : records) {
            if (addUserId.equals(record.get("addUserId"))) {
                return record;
            }
        }
        fail("未找到加签人 " + addUserId + " 的记录");
        return Map.of();
    }
}
