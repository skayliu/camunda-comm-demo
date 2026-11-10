package com.example.camundademo.config;

import com.example.camundademo.service.SubProcessDefService;
import jakarta.annotation.PreDestroy;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 部门沟通子流程的自动登记器。
 *
 * <p>职责只有一件事：任何以 {@code dept_comm_} 开头的流程被部署后，保证
 * {@code BIZ_SUBPROCESS_DEF_} 里有一条对应记录——没有就新增，有就按引擎版本更新。
 * 它<b>不做</b>业务判断：不猜部门、不覆盖人工设置的启停状态和说明。</p>
 *
 * <p>两条触发路径互为兜底：</p>
 * <ol>
 *   <li><b>部署时</b>：{@link DeploymentRegistryInterceptor} 把 deploymentId 放进队列，
 *       本类的后台线程拉到后按部署粒度登记；</li>
 *   <li><b>启动时</b>：应用就绪后做一次全量同步，覆盖 classpath 自动部署、
 *       以及在钩子生效前已经完成的部署。</li>
 * </ol>
 *
 * <p>为什么用 2 秒轮询而不是 Spring 事务的 afterCommit 回调：部署可能在
 * Camunda 引擎初始化阶段完成，此时 Spring 事务同步未必处于激活状态；
 * 队列 + 固定延迟消费对"部署事务何时提交"没有前提假设，任何时点都成立。
 * 队列是内存队列，进程重启会丢，但启动全量同步会把这些部署重新对齐。</p>
 */
@Component
public class DeptSubProcessDeploymentRegistrar {

    private static final Logger log = LoggerFactory.getLogger(DeptSubProcessDeploymentRegistrar.class);

    /**
     * 待登记的部署 ID，<b>按引擎配置实例分桶</b>。
     *
     * <p>不能用一个进程级共享队列：拦截器是非 Spring 对象，只能通过静态入口入队，
     * 而消费者（本类）是 Spring bean、每个应用上下文一个。同一个 JVM 里存在多个
     * 上下文时（例如集成测试缓存了多个 {@code @SpringBootTest} 上下文），共享队列会让
     * A 上下文的部署 ID 被 B 上下文的 consumer 取走；B 在自己的引擎里查不到该
     * deploymentId，最终既不登记也不归还，A 的注册表就永远等不到这条记录。</p>
     *
     * <p>用引擎配置实例作 key：它在一个引擎内唯一，天然把不同上下文隔开；
     * 生产只有一个上下文，行为与原先的单一队列完全一致。</p>
     */
    private static final Map<ProcessEngineConfigurationImpl, Queue<String>> PENDING = new ConcurrentHashMap<>();

    /** 单次最多处理多少条，避免一次部署风暴把启动线程占满 */
    private static final int BATCH_LIMIT = 20;

    /**
     * 部署已入队、但事务可能尚未提交时，消费线程会查不到这条部署。
     * 此时必须重入队，不能当作"没有 dept_comm_ 流程"直接丢弃——丢了就再也不会补，
     * 注册表会永久缺一条记录。重入队上限按 2 秒一轮算约 10 秒，覆盖一般慢提交。
     */
    private static final int MAX_VISIBILITY_RETRY = 5;

    /**
     * 逐条等待到上限后仍不可见的部署，<b>不再直接丢弃</b>：改为触发一次全量
     * {@link SubProcessDefService#syncFromEngine()} 兜底，并把该部署重新入队，
     * 让延迟提交的部署在后续轮次被补上。最多兜底这么多次，
     * 兜底也补不上才判定为确实不存在。
     *
     * <p>为什么必须兜底：全量测试时 33 个 Spring 上下文共用一个 JVM，Camunda 引擎 + H2
     * 并发、GC 抖动，部署可见性延迟超过 10 秒是真实发生过的；原实现此时直接放弃，
     * 注册表就永久少一条记录，只能人工点"从引擎同步"。</p>
     */
    private static final int MAX_FALLBACK_SYNC = 3;

    /** 取（必要时创建）某个引擎实例自己的待登记队列 */
    static Queue<String> pendingQueueOf(ProcessEngineConfigurationImpl engineConfig) {
        return PENDING.computeIfAbsent(engineConfig, key -> new ConcurrentLinkedQueue<>());
    }

    static void markDeployed(ProcessEngineConfigurationImpl engineConfig, String deploymentId) {
        if (deploymentId == null || deploymentId.isBlank()) {
            return;
        }
        if (engineConfig == null) {
            // 拿不到引擎配置就无法分桶，这条部署只能放弃；留一条日志，避免"注册表少了记录"查无实据
            log.warn("部署 {} 无法定位所属引擎配置，跳过自动登记（可在菜单中手动同步）", deploymentId);
            return;
        }
        Queue<String> queue = pendingQueueOf(engineConfig);
        // 同一部署可能被重复标记（部署拦截器与启动同步窗口重叠、重试重入队），
        // 队列里只留一份：否则同一条会被消费多次，日志与实际处理次数都对不上
        if (!queue.contains(deploymentId)) {
            queue.offer(deploymentId);
        }
    }

    private final SubProcessDefService subProcessDefService;

    /** 本实例所属引擎的配置，也是队列分桶的 key */
    private final ProcessEngineConfigurationImpl engineConfig;

    /** 本实例要消费的队列 */
    private final Queue<String> pending;

    /** 用于判断入队的部署是否已经可见（事务是否已提交），按 deploymentId 计数 */
    private final RepositoryService repositoryService;

    private final Map<String, Integer> visibilityRetries = new ConcurrentHashMap<>();

    /** 按 deploymentId 记录已触发过几次兜底同步 */
    private final Map<String, Integer> fallbackSyncs = new ConcurrentHashMap<>();

    /**
     * 累计计数：{@link #fallbackSyncs} 会随处理完成被清空，只能回答"此刻有谁在兜底"，
     * 回答不了"今天兜底过几次"。运维关心的是后者——它直接说明部署可见性是否在抖。
     */
    private final AtomicLong fallbackTotal = new AtomicLong();

    /** 累计放弃条数：>0 意味着注册表可能缺记录，必须让人看得见 */
    private final AtomicLong giveUpTotal = new AtomicLong();

    /** 累计处理完成（可见且登记逻辑已跑过）的部署条数，含"不是 dept_comm_ 流程"的空跑 */
    private final AtomicLong processedTotal = new AtomicLong();

    /**
     * 重试窗口，实例字段而非直接读常量：测试可以把窗口压到毫秒级，
     * 不必为了验证"超限后兜底"真等 40 秒。
     */
    private int maxVisibilityRetry = MAX_VISIBILITY_RETRY;

    private int maxFallbackSync = MAX_FALLBACK_SYNC;

    private ScheduledExecutorService worker;

    public DeptSubProcessDeploymentRegistrar(SubProcessDefService subProcessDefService,
                                             ProcessEngine processEngine) {
        this.subProcessDefService = subProcessDefService;
        this.engineConfig = (ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration();
        this.repositoryService = processEngine.getRepositoryService();
        this.pending = pendingQueueOf(engineConfig);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        // 先全量对齐：classpath 自动部署发生在引擎初始化阶段，队列里的 ID 此时可能还没消费
        syncQuietly("启动同步");
        worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dept-subprocess-registrar");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleWithFixedDelay(this::flushQuietly, 2, 2, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        if (worker != null) {
            worker.shutdownNow();
        }
        // 上下文销毁时摘掉自己的桶，避免长生命周期 JVM（测试/热重启）里残留
        PENDING.remove(engineConfig);
    }

    private void flushQuietly() {
        try {
            flushOnce();
        } catch (RuntimeException e) {
            log.warn("部门子流程自动登记线程异常", e);
        }
    }

    /**
     * 消费一轮：<b>先取快照，再逐条处理</b>。
     *
     * <p>不能写成 {@code while (poll() != null) handle()}：{@link #handleDeployment} 在
     * 不可见时会把同一条重新入队，而队列是同一个，于是本轮立刻又把它取出来——重试、
     * 兜底预算都在同一轮里以毫秒级速度烧完，2 秒的退避完全失效。生产上的表现是：
     * 一次 100 毫秒的提交延迟就能让某条部署当场被判定"兜底 5 次后仍无记录，放弃"。</p>
     *
     * <p>先取出本轮已存在的条目，处理期间重入队的自然留在队列里、等下一轮（2 秒后），
     * 退避窗口才真正生效。</p>
     *
     * @return 本轮取出的条数，便于测试断言"一条只处理一次"
     */
    int flushOnce() {
        List<String> batch = new ArrayList<>(BATCH_LIMIT);
        String deploymentId;
        while (batch.size() < BATCH_LIMIT && (deploymentId = pending.poll()) != null) {
            batch.add(deploymentId);
        }
        boolean fallbackRequested = false;
        for (String id : batch) {
            fallbackRequested |= handleDeployment(id);
        }
        if (fallbackRequested) {
            // 一次全量同步覆盖本轮所有"逐条等不到"的部署，比每条各查一遍更便宜
            syncQuietly("不可见部署兜底同步");
        }
        return batch.size();
    }

    /**
     * 处理队列里的单条部署。
     *
     * @return 是否需要在本轮结束后触发一次全量兜底同步
     */
    boolean handleDeployment(String deploymentId) {
        if (!deploymentVisible(deploymentId)) {
            // 部署命令已经返回、ID 也已经分配，但事务可能还没提交：此刻查不到是正常的，
            // 重入队等下一轮；连续多轮都查不到则转兜底，而不是直接丢弃。
            int tries = visibilityRetries.merge(deploymentId, 1, Integer::sum);
            if (tries < maxVisibilityRetry) {
                enqueue(deploymentId);
                return false;
            }
            visibilityRetries.remove(deploymentId);
            int fallbacks = fallbackSyncs.merge(deploymentId, 1, Integer::sum);
            if (fallbacks <= maxFallbackSync) {
                // 重新入队 + 本轮末尾全量同步：即使此刻仍不可见，下一轮同步也能补上
                enqueue(deploymentId);
                fallbackTotal.incrementAndGet();
                log.warn("部署 {} 连续 {} 轮在引擎中不可见，改为兜底同步并重试（第 {}/{} 次）",
                        deploymentId, tries, fallbacks, maxFallbackSync);
                return true;
            }
            fallbackSyncs.remove(deploymentId);
            giveUpTotal.incrementAndGet();
            log.warn("部署 {} 连续 {} 轮不可见、兜底 {} 次后仍无记录，放弃自动登记（可在菜单中手动同步）",
                    deploymentId, tries, fallbacks - 1);
            return false;
        }
        visibilityRetries.remove(deploymentId);
        fallbackSyncs.remove(deploymentId);
        try {
            Map<String, Integer> stat = subProcessDefService.registerDeployment(deploymentId);
            processedTotal.incrementAndGet();
            if (stat.getOrDefault("created", 0) > 0 || stat.getOrDefault("updated", 0) > 0) {
                log.info("部署 {} 自动登记完成：新增 {}，更新 {}，无变化 {}",
                        deploymentId, stat.get("created"), stat.get("updated"), stat.get("skipped"));
            }
        } catch (RuntimeException e) {
            // 单次失败不重试：启动全量同步和菜单里的"从引擎同步"都能补上，
            // 无限重试反而会在表结构异常时刷满日志
            log.warn("部署 {} 自动登记失败，可在菜单中手动同步", deploymentId, e);
        }
        return false;
    }

    /** 入队并去重：队列表很短（BATCH_LIMIT = 20），contains 的成本可忽略 */
    private void enqueue(String deploymentId) {
        if (!pending.contains(deploymentId)) {
            pending.offer(deploymentId);
        }
    }

    /** 仅供测试：模拟消费线程"取一条并处理"，返回是否请求兜底同步 */
    boolean pollAndHandleOne() {
        String deploymentId = pending.poll();
        return deploymentId != null && handleDeployment(deploymentId);
    }

    /**
     * 诊断快照，供测试超时信息、管理页与线上排障使用（只读，不改状态）。
     *
     * <p>自动登记失败时最想知道三件事：队列里还压着多少条、某条部署重试到第几轮、
     * 消费线程是否还活着。管理页另外要看两个累计值：兜底过几次、放弃过几次——
     * 后者大于 0 就意味着注册表可能真的缺记录，不能只看"队列已空"就判定健康。</p>
     */
    public Map<String, Object> diagnostics() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("pendingCount", pending.size());
        snapshot.put("visibilityRetries", new LinkedHashMap<>(visibilityRetries));
        snapshot.put("fallbackSyncs", new LinkedHashMap<>(fallbackSyncs));
        snapshot.put("consumerAlive", worker != null && !worker.isShutdown());
        // 区分"还没启动"与"启动后被关停"：前者在启动阶段正常，后者才是故障
        snapshot.put("consumerStarted", worker != null);
        // 当前正卡在逐条重试里的部署数（fallbackSyncs 同理，表示正卡在兜底里的部署数）
        snapshot.put("visibilityRetrying", visibilityRetries.size());
        snapshot.put("fallbackWaiting", fallbackSyncs.size());
        snapshot.put("fallbackTotal", fallbackTotal.get());
        snapshot.put("giveUpTotal", giveUpTotal.get());
        snapshot.put("processedTotal", processedTotal.get());
        snapshot.put("maxVisibilityRetry", maxVisibilityRetry);
        snapshot.put("maxFallbackSync", maxFallbackSync);
        return snapshot;
    }

    /** 仅供测试压缩重试窗口，生产不要调用 */
    void overrideRetryLimits(int visibilityRetry, int fallbackSync) {
        this.maxVisibilityRetry = Math.max(1, visibilityRetry);
        this.maxFallbackSync = Math.max(0, fallbackSync);
    }

    /**
     * 判断这条部署是否已经在本引擎可见。
     *
     * <p>用 DeploymentQuery 而不是 ProcessDefinitionQuery：纯资源部署（只有图片、没有流程定义）
     * 也会入队，它对注册表没有意义但确实存在，不该因此被判成"不可见"反复重试。</p>
     */
    private boolean deploymentVisible(String deploymentId) {
        return repositoryService.createDeploymentQuery().deploymentId(deploymentId).count() > 0;
    }

    private void syncQuietly(String scene) {
        try {
            Map<String, Integer> stat = subProcessDefService.syncFromEngine();
            log.info("{} 部门子流程注册表：新增 {}，更新 {}，无变化 {}",
                    scene, stat.get("created"), stat.get("updated"), stat.get("skipped"));
        } catch (RuntimeException e) {
            // 注册表不可用不应阻止应用启动：业务侧读不到登记只会提示"流程不可用"，
            // 比整个服务起不来影响小得多
            log.warn("{} 失败，部门子流程注册表可能不是最新", scene, e);
        }
    }
}
