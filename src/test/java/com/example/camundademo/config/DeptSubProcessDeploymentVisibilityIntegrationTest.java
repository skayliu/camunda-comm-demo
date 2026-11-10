package com.example.camundademo.config;

import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.repository.Deployment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动登记器在<b>真实引擎</b>上的"部署延迟可见"用例。
 *
 * <p>{@link DeptSubProcessDeploymentRegistrarTest} 用 mock 验证了退避策略的分支，
 * 但那组用例的"不可见"是假装的：它只把查询结果设成 0，没有证明"引擎里真的有一条
 * 已分配 ID、但其他连接看不到的部署"。这组用例补上这一段：</p>
 *
 * <ol>
 *   <li>在一个 Spring 事务里执行真实部署，拿到引擎分配的 deploymentId；</li>
 *   <li><b>事务不提交</b>，从另一条连接（另一个线程）查询该部署——查不到。
 *       这就是生产里"部署命令已返回、事务还没提交"的真实形态；</li>
 *   <li>部署拦截器此时已经把 ID 入队，消费线程按 2 秒一轮拉到它、查不到、转兜底同步；</li>
 *   <li>事务提交后，注册表最终被补齐，且版本/名称/部门都来自引擎，不是占位数据。</li>
 * </ol>
 *
 * <p>为什么必须用真实引擎：mock 无法复现"同一 ID 在一个连接可见、在另一个连接不可见"
 * 这个隔离语义，而这正是退避逻辑存在的全部理由。单独一个内存库（{@code deptdelay}），
 * 避免与业务链路的用例互相污染。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:deptdelay;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class DeptSubProcessDeploymentVisibilityIntegrationTest {

    /** 必须带 dept_comm_ 前缀，否则注册器按业务约定不会登记 */
    private static final String DELAYED_KEY = "dept_comm_delayed_visibility";
    private static final String DELAYED_NAME = "延迟可见自动登记";
    private static final String ROLLBACK_KEY = "dept_comm_rolled_back_visibility";
    private static final String ROLLBACK_NAME = "回滚部署不留痕";
    private static final String NORMAL_KEY = "dept_comm_normal_visibility";
    private static final String NORMAL_NAME = "正常可见自动登记";
    private static final String DEPT_ID = "dept_it";

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeptSubProcessDeploymentRegistrar registrar;

    @AfterEach
    void restoreRetryWindow() {
        // 用例把窗口压小了，恢复默认值，避免同一上下文里的后续用例按小时级的窗口跑
        registrar.overrideRetryLimits(5, 3);
    }

    // ==================== 1. 未提交的部署 ====================

    @Test
    void deploymentQueuedBeforeCommitIsRegisteredAfterCommit() throws Exception {
        // 第一轮不可见就转兜底：把"是否走兜底分支"从时序运气变成确定结果
        registrar.overrideRetryLimits(1, 5);

        AtomicReference<String> deploymentId = new AtomicReference<>();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            Deployment deployment = repositoryService.createDeployment()
                    .name(DELAYED_NAME)
                    .addString(DELAYED_KEY + ".bpmn", bpmn(DELAYED_KEY, DELAYED_NAME, DEPT_ID))
                    .deploy();
            String id = deployment.getId();
            deploymentId.set(id);

            // 同一事务内可见：证明部署命令确实执行了，ID 也是引擎真实分配的
            assertEquals(1L, visibleDeployments(id),
                    "同一事务内应能看到刚部署的记录，否则下面的“不可见”没有对照意义");

            // 关键对照：事务未提交，另一条连接看不到
            assertEquals(0L, visibleDeploymentsFromAnotherThread(id),
                    "部署事务未提交时，另一条连接不应看到该部署——这是“延迟可见”的判定依据；"
                            + "若这里为 1，说明部署命令没有加入外层事务，用例失去意义");

            // 拦截器在 deploy() 返回后已入队，此时队列里的 ID 在引擎里还查不到。
            // 保持不提交，让消费线程至少跑完一轮（轮询间隔 2 秒）。
            sleep(4500);

            Map<String, Object> snapshot = registrar.diagnostics();
            assertTrue(idsOf(snapshot, "fallbackSyncs").containsKey(id),
                    "未提交期间消费线程应判定为“不可见”并进入兜底分支，实际诊断=" + snapshot);
            assertEquals(0, registeredRows(DELAYED_KEY),
                    "部署尚未提交，注册表不应出现该流程（否则等于给不存在的部署建了记录）");
        });

        String id = deploymentId.get();
        assertNotNull(id, "用例没有拿到 deploymentId");

        // 提交之后：注册表最终被补齐（逐条重试或兜底同步任一路径都算达标）
        Map<String, Object> row = awaitRegistered(DELAYED_KEY, 30_000, id);
        assertEquals(1, ((Number) row.get("VERSION_")).intValue(), "应登记引擎里的版本号 1");
        assertEquals(DELAYED_NAME, row.get("PROCESS_NAME_"), "流程名应取自引擎，而不是占位字符串");
        assertEquals(DEPT_ID, row.get("DEPT_ID_"), "部门应取自 BPMN 的 camunda:properties/deptId");
        assertEquals(1, registeredRows(DELAYED_KEY), "同一 key 只应有一条登记记录");

        // 收尾：该部署不应再被反复重试（重试计数要么已清空，要么不再增长）
        Map<String, Object> snapshot = registrar.diagnostics();
        assertTrue(idsOf(snapshot, "fallbackSyncs").getOrDefault(id, 0) <= 5,
                "兜底次数不应超出预算，实际诊断=" + snapshot);
    }

    // ==================== 2. 正常可见的部署 ====================

    @Test
    void visibleDeploymentIsRegisteredWithoutFallback() {
        // 这条用例守住另一侧：加固退避之后，正常部署仍然走"第一轮就登记"的快路径，
        // 不会被无谓地拖进兜底同步
        registrar.overrideRetryLimits(1, 5);

        Deployment deployment = repositoryService.createDeployment()
                .name(NORMAL_NAME)
                .addString(NORMAL_KEY + ".bpmn", bpmn(NORMAL_KEY, NORMAL_NAME, DEPT_ID))
                .deploy();
        String id = deployment.getId();

        Map<String, Object> row = awaitRegistered(NORMAL_KEY, 20_000, id);
        assertEquals(NORMAL_NAME, row.get("PROCESS_NAME_"), "流程名应取自引擎");

        Map<String, Object> snapshot = registrar.diagnostics();
        assertEquals(0, idsOf(snapshot, "fallbackSyncs").getOrDefault(id, 0),
                "部署本来就可见，不应触发兜底同步，实际诊断=" + snapshot);
        assertEquals(0, idsOf(snapshot, "visibilityRetries").getOrDefault(id, 0),
                "可见部署不应产生重试计数，实际诊断=" + snapshot);
    }

    // ==================== 3. 回滚的部署 ====================

    @Test
    void rolledBackDeploymentLeavesNoRowAndDoesNotClogTheQueue() {
        // 回滚后这条部署永远不会出现：重试与兜底都用压缩窗口跑完，验证它既不留脏记录、
        // 也不会永远挂在队列里反复触发全量同步
        registrar.overrideRetryLimits(1, 1);

        AtomicReference<String> deploymentId = new AtomicReference<>();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            Deployment deployment = repositoryService.createDeployment()
                    .name(ROLLBACK_NAME)
                    .addString(ROLLBACK_KEY + ".bpmn", bpmn(ROLLBACK_KEY, ROLLBACK_NAME, DEPT_ID))
                    .deploy();
            deploymentId.set(deployment.getId());
            assertEquals(1L, visibleDeployments(deployment.getId()),
                    "回滚前事务内应能看到这次部署，否则用例证明不了“回滚”这件事");
            status.setRollbackOnly();
        });

        String id = deploymentId.get();
        assertNotNull(id, "用例没有拿到 deploymentId");
        assertEquals(0L, visibleDeploymentsFromAnotherThread(id), "事务回滚后该部署不应存在");

        // 等消费线程跑完：重试 → 兜底 → 放弃（窗口已压到 1/1，两轮轮询即可）
        sleep(6_000);

        assertEquals(0, registeredRows(ROLLBACK_KEY),
                "回滚的部署不得在注册表留下记录——这正是拦截器只入队、不直接写库的理由");
        Map<String, Object> snapshot = registrar.diagnostics();
        assertTrue(idsOf(snapshot, "visibilityRetries").isEmpty(),
                "放弃后应清掉该部署的重试计数，实际=" + snapshot);
        assertTrue(idsOf(snapshot, "fallbackSyncs").isEmpty(),
                "放弃后应清掉该部署的兜底计数，实际=" + snapshot);
        assertEquals(0, snapshot.get("pendingCount"),
                "放弃后不应继续占着队列空转，实际=" + snapshot);
    }

    // ==================== 辅助方法 ====================

    /** 当前连接（事务内即本事务）看到的该部署条数 */
    private long visibleDeployments(String deploymentId) {
        return repositoryService.createDeploymentQuery().deploymentId(deploymentId).count();
    }

    /**
     * 从另一条连接查询部署条数。
     *
     * <p>必须换线程：Camunda 的查询上下文按线程绑定，同线程只会复用当前事务的连接，
     * 那样永远看得到自己未提交的数据，无法模拟"延迟可见"。</p>
     */
    private long visibleDeploymentsFromAnotherThread(String deploymentId) {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Long> future = pool.submit(() -> visibleDeployments(deploymentId));
            return future.get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待跨连接可见性查询被中断", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("跨连接可见性查询失败：" + e.getMessage(), e);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 轮询等待注册表出现该 key 的记录 */
    private Map<String, Object> awaitRegistered(String key, long timeoutMillis, String deploymentId) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        Map<String, Object> last = null;
        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM BIZ_SUBPROCESS_DEF_ WHERE PROCESS_KEY_ = ?", key);
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
            sleep(200);
        }
        throw new IllegalStateException("等待 " + key + " 自动登记超时（部署 " + deploymentId
                + "，此刻引擎可见=" + visibleDeployments(deploymentId)
                + "，注册器=" + registrar.diagnostics() + "，最后一条记录=" + last + "）");
    }

    private int registeredRows(String key) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM BIZ_SUBPROCESS_DEF_ WHERE PROCESS_KEY_ = ?", Integer.class, key);
        return count == null ? 0 : count;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> idsOf(Map<String, Object> snapshot, String field) {
        Object value = snapshot.get(field);
        return value instanceof Map<?, ?> map ? (Map<String, Integer>) map : Map.of();
    }

    private static String bpmn(String key, String name, String deptId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                             targetNamespace="http://example.com/deptdelay">
                  <process id="%s" name="%s" isExecutable="true" camunda:historyTimeToLive="180">
                    <extensionElements>
                      <camunda:properties>
                        <camunda:property name="deptId" value="%s"/>
                      </camunda:properties>
                    </extensionElements>
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key, name, deptId);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
