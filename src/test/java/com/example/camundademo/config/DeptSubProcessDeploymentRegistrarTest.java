package com.example.camundademo.config;

import com.example.camundademo.service.SubProcessDefService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.repository.DeploymentQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自动登记器的退避策略：<b>查不到不等于不存在，更不等于可以丢</b>。
 *
 * <p>这组用例锁住升级后的行为：部署连续几轮在引擎中不可见时，先重入队；到上限后
 * 触发一次全量兜底同步并继续排队；只有兜底预算也用完才放弃。原实现在上限处直接丢弃，
 * 全量测试里一次 GC 抖动就可能让注册表永久少一条记录。</p>
 *
 * <p>纯 mock 驱动，不启动 Spring 上下文、不起消费线程，秒级完成。</p>
 */
class DeptSubProcessDeploymentRegistrarTest {

    private static final String DEP_ID = "dep-1";

    private SubProcessDefService subProcessDefService;
    private DeploymentQuery deploymentQuery;
    private ProcessEngineConfigurationImpl engineConfig;
    private DeptSubProcessDeploymentRegistrar registrar;

    @BeforeEach
    void setUp() {
        subProcessDefService = mock(SubProcessDefService.class);
        engineConfig = mock(ProcessEngineConfigurationImpl.class);
        RepositoryService repositoryService = mock(RepositoryService.class);
        deploymentQuery = mock(DeploymentQuery.class);

        ProcessEngine processEngine = mock(ProcessEngine.class);
        when(processEngine.getProcessEngineConfiguration()).thenReturn(engineConfig);
        when(processEngine.getRepositoryService()).thenReturn(repositoryService);
        when(repositoryService.createDeploymentQuery()).thenReturn(deploymentQuery);
        when(deploymentQuery.deploymentId(anyString())).thenReturn(deploymentQuery);

        when(subProcessDefService.syncFromEngine())
                .thenReturn(Map.of("created", 0, "updated", 0, "skipped", 0));

        registrar = new DeptSubProcessDeploymentRegistrar(subProcessDefService, processEngine);
    }

    @AfterEach
    void tearDown() {
        // 摘掉本用例在静态桶里留下的队列，避免同类用例互相干扰
        registrar.shutdown();
    }

    @Test
    void visibleDeploymentIsRegisteredAndCountersCleared() {
        when(deploymentQuery.count()).thenReturn(1L);
        when(subProcessDefService.registerDeployment(DEP_ID))
                .thenReturn(Map.of("created", 1, "updated", 0, "skipped", 0));

        assertFalse(registrar.handleDeployment(DEP_ID), "可见且已登记不应请求兜底同步");

        verify(subProcessDefService).registerDeployment(DEP_ID);
        Map<String, Object> snapshot = registrar.diagnostics();
        assertEquals(0, snapshot.get("pendingCount"), "已处理完不应留在队列");
        assertTrue(retries(snapshot, "visibilityRetries").isEmpty(), "登记成功后重试计数必须清空");
        assertTrue(retries(snapshot, "fallbackSyncs").isEmpty(), "登记成功后兜底计数必须清空");
    }

    @Test
    void invisibleDeploymentIsRequeuedInsteadOfDropped() {
        when(deploymentQuery.count()).thenReturn(0L);
        registrar.overrideRetryLimits(3, 1);
        DeptSubProcessDeploymentRegistrar.markDeployed(engineConfig, DEP_ID);

        assertFalse(registrar.pollAndHandleOne(), "未到上限只需重入队，不必兜底");

        verify(subProcessDefService, never()).registerDeployment(anyString());
        Map<String, Object> snapshot = registrar.diagnostics();
        assertEquals(1, snapshot.get("pendingCount"), "查不到应重入队等待下一轮，不能丢");
        assertEquals(1, retries(snapshot, "visibilityRetries").get(DEP_ID));
    }

    @Test
    void duplicateMarksKeepOnlyOneQueueEntry() {
        when(deploymentQuery.count()).thenReturn(0L);
        DeptSubProcessDeploymentRegistrar.markDeployed(engineConfig, DEP_ID);
        DeptSubProcessDeploymentRegistrar.markDeployed(engineConfig, DEP_ID);

        assertEquals(1, registrar.diagnostics().get("pendingCount"),
                "同一部署重复标记只应排队一次，否则会被重复消费、日志次数也对不上");
    }

    @Test
    void exhaustedVisibilityRequestsFallbackAndKeepsRetrying() {
        when(deploymentQuery.count()).thenReturn(0L);
        registrar.overrideRetryLimits(2, 1);
        DeptSubProcessDeploymentRegistrar.markDeployed(engineConfig, DEP_ID);

        assertFalse(registrar.pollAndHandleOne(), "第一轮未到上限");
        assertTrue(registrar.pollAndHandleOne(), "到上限应请求一次全量兜底同步");

        Map<String, Object> snapshot = registrar.diagnostics();
        assertEquals(1, snapshot.get("pendingCount"),
                "兜底期间必须继续排队：否则同步时仍不可见的部署就彻底补不上了");
        assertEquals(1, retries(snapshot, "fallbackSyncs").get(DEP_ID));
        assertTrue(retries(snapshot, "visibilityRetries").isEmpty(), "进入兜底后逐条重试计数归零");
    }

    @Test
    void givesUpOnlyAfterFallbackBudgetIsUsedUp() {
        when(deploymentQuery.count()).thenReturn(0L);
        registrar.overrideRetryLimits(1, 0);
        DeptSubProcessDeploymentRegistrar.markDeployed(engineConfig, DEP_ID);

        assertFalse(registrar.pollAndHandleOne(), "兜底预算为 0 时不应再请求同步");

        Map<String, Object> snapshot = registrar.diagnostics();
        assertEquals(0, snapshot.get("pendingCount"), "放弃后不应留在队列里空转");
        assertTrue(retries(snapshot, "fallbackSyncs").isEmpty(), "放弃时应清掉该部署的兜底计数");
    }

    @Test
    void oneFlushCycleTouchesEachInvisibleDeploymentOnlyOnce() {
        when(deploymentQuery.count()).thenReturn(0L);
        registrar.overrideRetryLimits(3, 1);
        DeptSubProcessDeploymentRegistrar.markDeployed(engineConfig, DEP_ID);

        int handled = registrar.flushOnce();

        assertEquals(1, handled, "一轮只能取出并处理一条；原实现会在 while 里立刻把重入队的同一条再取出");
        Map<String, Object> snapshot = registrar.diagnostics();
        assertEquals(1, retries(snapshot, "visibilityRetries").get(DEP_ID),
                "一轮只应记一次重试——记成 2 次说明同一条在本轮被消费了两遍，退避窗口失效");
        assertEquals(1, snapshot.get("pendingCount"), "重入队后应留在队列等下一轮（2 秒后），而不是本轮反复取出");
    }

    @Test
    void fallbackBudgetIsNotBurnedWithinOneCycle() {
        when(deploymentQuery.count()).thenReturn(0L);
        // 每轮都进入兜底分支：原实现会在一轮内连续兜底，预算瞬间用完
        registrar.overrideRetryLimits(1, 3);
        DeptSubProcessDeploymentRegistrar.markDeployed(engineConfig, DEP_ID);

        registrar.flushOnce();

        Map<String, Object> snapshot = registrar.diagnostics();
        assertEquals(1, retries(snapshot, "fallbackSyncs").get(DEP_ID),
                "一轮只应消耗一次兜底预算，实际=" + snapshot);
        verify(subProcessDefService).syncFromEngine();
    }

    @Test
    void registrationFailureDoesNotThrowOrRequeue() {
        when(deploymentQuery.count()).thenReturn(1L);
        when(subProcessDefService.registerDeployment(DEP_ID))
                .thenThrow(new IllegalStateException("表结构异常"));

        assertFalse(registrar.handleDeployment(DEP_ID), "单次登记失败不应触发兜底同步风暴");

        verify(subProcessDefService).registerDeployment(DEP_ID);
        assertEquals(0, registrar.diagnostics().get("pendingCount"), "失败不重试，交给启动/手动同步兜底");
    }

    /**
     * 管理页要看到的是"累计兜底过几次、放弃过几条"，而不是"此刻谁在兜底"：
     * 后者随处理完成就被清空，故障过去后页面会显示一切正常。
     */
    @Test
    void diagnosticsAccumulateFallbackAndGiveUpAcrossDeployments() {
        when(deploymentQuery.count()).thenReturn(0L);
        registrar.overrideRetryLimits(1, 1);

        // 第一条：兜底 1 次后仍不可见 → 放弃
        DeptSubProcessDeploymentRegistrar.markDeployed(engineConfig, "dep-a");
        assertTrue(registrar.pollAndHandleOne(), "第一次不可见应请求兜底");
        assertFalse(registrar.pollAndHandleOne(), "兜底预算用尽后应放弃");

        // 第二条：只兜底一次，不放弃
        DeptSubProcessDeploymentRegistrar.markDeployed(engineConfig, "dep-b");
        assertTrue(registrar.pollAndHandleOne(), "第二条同样应进入兜底");

        Map<String, Object> snapshot = registrar.diagnostics();
        assertEquals(2L, snapshot.get("fallbackTotal"), "累计兜底次数应跨部署累加");
        assertEquals(1L, snapshot.get("giveUpTotal"), "只有第一条被放弃");
        // 此刻的"正在兜底"只应该有第二级，第一条已放弃并清空
        assertEquals(1, snapshot.get("fallbackWaiting"));
        assertEquals(1, snapshot.get("pendingCount"), "第二条仍在队列里等待下一轮");
    }

    @Test
    void diagnosticsReportRetryingAndProcessedCounts() {
        when(deploymentQuery.count()).thenReturn(0L);
        registrar.overrideRetryLimits(3, 1);
        DeptSubProcessDeploymentRegistrar.markDeployed(engineConfig, DEP_ID);
        registrar.pollAndHandleOne();

        Map<String, Object> retrying = registrar.diagnostics();
        assertEquals(1, retrying.get("visibilityRetrying"), "逐条重试中的部署数要能被运维看到");
        assertEquals(0L, retrying.get("processedTotal"), "重试阶段还没有处理完成");

        when(deploymentQuery.count()).thenReturn(1L);
        when(subProcessDefService.registerDeployment(DEP_ID))
                .thenReturn(Map.of("created", 1, "updated", 0, "skipped", 0));
        registrar.pollAndHandleOne();

        Map<String, Object> done = registrar.diagnostics();
        assertEquals(0, done.get("visibilityRetrying"), "登记成功后不再算作重试中");
        assertEquals(1L, done.get("processedTotal"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> retries(Map<String, Object> snapshot, String field) {
        return (Map<String, Integer>) snapshot.get(field);
    }
}
