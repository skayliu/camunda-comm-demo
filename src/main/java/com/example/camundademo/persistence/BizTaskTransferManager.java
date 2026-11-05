package com.example.camundademo.persistence;

import com.example.camundademo.entity.BizTaskTransfer;
import org.camunda.bpm.engine.impl.interceptor.Session;
import org.camunda.bpm.engine.impl.interceptor.SessionFactory;
import org.camunda.bpm.engine.impl.persistence.AbstractManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BizTaskTransfer 管理器：转办记录的写入与查询。
 *
 * <p>与 {@link BizCommTaskManager} 一样走 Camunda 的 DbSqlSession，
 * 保证「改派 + 落记录」处于同一个引擎命令、同一个事务里，不会出现改派成功但没有记录的中间态。</p>
 */
public class BizTaskTransferManager extends AbstractManager implements Session {

    public static final String MAPPER_NAMESPACE = "com.example.camundademo.persistence.BizTaskTransferManager";

    public void insert(BizTaskTransfer transfer) {
        getDbEntityManager().insert(transfer);
    }

    public List<BizTaskTransfer> findByTaskId(String taskId) {
        return selectList(MAPPER_NAMESPACE + ".selectByTaskId", taskId);
    }

    /**
     * 按待办批量查询（待办列表用）：单条查询会退化成 N 次 SQL。
     *
     * <p>Camunda 的 DbSqlSession 把单集合参数只暴露为 {@code collection}，
     * 而 XML 里 foreach 用的是 {@code list}，这里显式包一层 Map 对齐参数名。</p>
     */
    public List<BizTaskTransfer> findByTaskIds(Collection<String> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, Object> params = new HashMap<>();
        params.put("list", taskIds);
        return selectList(MAPPER_NAMESPACE + ".selectByTaskIds", params);
    }

    /** 按流程实例查询：流程跟踪页展示转办痕迹、以及转办参与人的可见性判断 */
    public List<BizTaskTransfer> findByProcessInstanceId(String processInstanceId) {
        return selectList(MAPPER_NAMESPACE + ".selectByProcessInstanceId", processInstanceId);
    }

    @SuppressWarnings("unchecked")
    private List<BizTaskTransfer> selectList(String statement, Object parameter) {
        // 同一命令内重复查询时清一级缓存，避免读到旧结果
        getDbSqlSession().getSqlSession().clearCache();
        return (List<BizTaskTransfer>) getDbSqlSession().selectList(statement, parameter);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }

    public static class Factory implements SessionFactory {
        @Override
        public Class<?> getSessionType() {
            return BizTaskTransferManager.class;
        }

        @Override
        public Session openSession() {
            return new BizTaskTransferManager();
        }
    }
}
