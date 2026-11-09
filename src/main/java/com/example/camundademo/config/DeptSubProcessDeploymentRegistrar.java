package com.example.camundademo.config;

import com.example.camundademo.service.SubProcessDefService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

    /** 待登记的部署 ID；由引擎命令线程写入，由本类的后台线程消费 */
    private static final Queue<String> PENDING = new ConcurrentLinkedQueue<>();

    /** 单次最多处理多少条，避免一次部署风暴把启动线程占满 */
    private static final int BATCH_LIMIT = 20;

    static void markDeployed(String deploymentId) {
        if (deploymentId != null && !deploymentId.isBlank()) {
            PENDING.offer(deploymentId);
        }
    }

    private final SubProcessDefService subProcessDefService;

    private ScheduledExecutorService worker;

    public DeptSubProcessDeploymentRegistrar(SubProcessDefService subProcessDefService) {
        this.subProcessDefService = subProcessDefService;
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
    }

    private void flushQuietly() {
        try {
            int processed = 0;
            String deploymentId;
            while (processed < BATCH_LIMIT && (deploymentId = PENDING.poll()) != null) {
                processed++;
                try {
                    Map<String, Integer> stat = subProcessDefService.registerDeployment(deploymentId);
                    if (stat.getOrDefault("created", 0) > 0 || stat.getOrDefault("updated", 0) > 0) {
                        log.info("部署 {} 自动登记完成：新增 {}，更新 {}，无变化 {}",
                                deploymentId, stat.get("created"), stat.get("updated"), stat.get("skipped"));
                    }
                } catch (RuntimeException e) {
                    // 单次失败不重试：启动全量同步和菜单里的"从引擎同步"都能补上，
                    // 无限重试反而会在表结构异常时刷满日志
                    log.warn("部署 {} 自动登记失败，可在菜单中手动同步", deploymentId, e);
                }
            }
        } catch (RuntimeException e) {
            log.warn("部门子流程自动登记线程异常", e);
        }
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
