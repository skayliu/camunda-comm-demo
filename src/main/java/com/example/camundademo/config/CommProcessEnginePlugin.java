package com.example.camundademo.config;

import com.example.camundademo.entity.BizAgentRule;
import com.example.camundademo.entity.BizCommSession;
import com.example.camundademo.entity.BizCommTask;
import com.example.camundademo.entity.BizTaskDelegate;
import com.example.camundademo.entity.BizTaskExt;
import com.example.camundademo.entity.BizTaskTransfer;
import com.example.camundademo.interceptor.CommunicationCommandInterceptor;
import com.example.camundademo.persistence.BizAgentRuleManager;
import com.example.camundademo.persistence.BizCommSessionManager;
import com.example.camundademo.persistence.BizCommTaskManager;
import com.example.camundademo.persistence.BizTaskDelegateManager;
import com.example.camundademo.persistence.BizTaskExtManager;
import com.example.camundademo.persistence.BizTaskTransferManager;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.SqlSessionFactory;
import org.camunda.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.camunda.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.db.sql.DbSqlSessionFactory;
import org.camunda.bpm.engine.impl.interceptor.CommandInterceptor;
import org.camunda.bpm.engine.impl.interceptor.SessionFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Camunda引擎插件：注册自定义MyBatis映射、实体、BPMN监听器、命令拦截器。
 * <p>
 * 注意：Camunda 7.24 已移除 {@code customMybatisXMLMappers}，自定义 mapper XML
 * 需要在 SqlSessionFactory 构建完成后（postInit）显式装载。
 */
public class CommProcessEnginePlugin extends AbstractProcessEnginePlugin {

    private static final String[] MAPPER_RESOURCES = {
            "mapper/BizTaskExt.xml",
            "mapper/BizCommSession.xml",
            "mapper/BizCommTask.xml",
            "mapper/BizTaskTransfer.xml",
            "mapper/BizTaskDelegate.xml",
            "mapper/BizAgentRule.xml"
    };

    @Override
    public void preInit(ProcessEngineConfigurationImpl config) {
        // 1. 注册BPMN全局解析监听器
        List<BpmnParseListener> preParseListeners = config.getCustomPreBPMNParseListeners();
        if (preParseListeners == null) {
            preParseListeners = new ArrayList<>();
            config.setCustomPreBPMNParseListeners(preParseListeners);
        }
        preParseListeners.add(new GlobalUserTaskParseListener());
        // 流程级监听：主流程结束时级联终止部门沟通子流程，避免孤儿实例
        preParseListeners.add(new GlobalProcessParseListener());

        // 2. 注册自定义SessionFactory
        List<SessionFactory> sessionFactories = config.getCustomSessionFactories();
        if (sessionFactories == null) {
            sessionFactories = new ArrayList<>();
            config.setCustomSessionFactories(sessionFactories);
        }
        sessionFactories.add(new BizTaskExtManager.Factory());
        sessionFactories.add(new BizCommSessionManager.Factory());
        sessionFactories.add(new BizCommTaskManager.Factory());
        sessionFactories.add(new BizTaskTransferManager.Factory());
        sessionFactories.add(new BizTaskDelegateManager.Factory());
        sessionFactories.add(new BizAgentRuleManager.Factory());

        // 3. 注册自定义命令拦截器（审批时校验沟通状态）
        // 必须放入 post 链：它位于 CommandContextInterceptor 之后，
        // 此时 CommandContext 才可用，且命令链尚未构建完成。
        List<CommandInterceptor> postInterceptors = config.getCustomPostCommandInterceptorsTxRequired();
        if (postInterceptors == null) {
            postInterceptors = new ArrayList<>();
            config.setCustomPostCommandInterceptorsTxRequired(postInterceptors);
        }
        postInterceptors.add(new CommunicationCommandInterceptor());
    }

    @Override
    public void postInit(ProcessEngineConfigurationImpl config) {
        // 1. 装载自定义 mapper XML（此时 SqlSessionFactory 已构建完成）
        loadMapperXml(config);

        // 2. 注册自定义实体的 insert/update/delete/select 语句映射
        DbSqlSessionFactory dbSqlSessionFactory = config.getDbSqlSessionFactory();
        if (dbSqlSessionFactory == null) {
            throw new IllegalStateException("DbSqlSessionFactory 未初始化，无法注册自定义实体映射");
        }

        Map<Class<?>, String> insertMappings = new HashMap<>(dbSqlSessionFactory.getInsertStatements());
        Map<Class<?>, String> updateMappings = new HashMap<>(dbSqlSessionFactory.getUpdateStatements());
        Map<Class<?>, String> deleteMappings = new HashMap<>(dbSqlSessionFactory.getDeleteStatements());
        Map<Class<?>, String> selectMappings = new HashMap<>(dbSqlSessionFactory.getSelectStatements());

        insertMappings.put(BizTaskExt.class, "insertBizTaskExt");
        updateMappings.put(BizTaskExt.class, "updateBizTaskExt");
        deleteMappings.put(BizTaskExt.class, "deleteBizTaskExt");
        selectMappings.put(BizTaskExt.class, "selectBizTaskExt");

        insertMappings.put(BizCommSession.class, "insertBizCommSession");
        updateMappings.put(BizCommSession.class, "updateBizCommSession");
        deleteMappings.put(BizCommSession.class, "deleteBizCommSession");
        selectMappings.put(BizCommSession.class, "selectBizCommSession");

        insertMappings.put(BizCommTask.class, "insertBizCommTask");
        updateMappings.put(BizCommTask.class, "updateBizCommTask");
        deleteMappings.put(BizCommTask.class, "deleteBizCommTask");
        selectMappings.put(BizCommTask.class, "selectBizCommTask");

        insertMappings.put(BizTaskTransfer.class, "insertBizTaskTransfer");
        updateMappings.put(BizTaskTransfer.class, "updateBizTaskTransfer");
        deleteMappings.put(BizTaskTransfer.class, "deleteBizTaskTransfer");
        selectMappings.put(BizTaskTransfer.class, "selectBizTaskTransfer");

        insertMappings.put(BizTaskDelegate.class, "insertBizTaskDelegate");
        updateMappings.put(BizTaskDelegate.class, "updateBizTaskDelegate");
        deleteMappings.put(BizTaskDelegate.class, "deleteBizTaskDelegate");
        selectMappings.put(BizTaskDelegate.class, "selectBizTaskDelegate");

        insertMappings.put(BizAgentRule.class, "insertBizAgentRule");
        updateMappings.put(BizAgentRule.class, "updateBizAgentRule");
        deleteMappings.put(BizAgentRule.class, "deleteBizAgentRule");
        selectMappings.put(BizAgentRule.class, "selectBizAgentRule");

        dbSqlSessionFactory.setInsertStatements(insertMappings);
        dbSqlSessionFactory.setUpdateStatements(updateMappings);
        dbSqlSessionFactory.setDeleteStatements(deleteMappings);
        dbSqlSessionFactory.setSelectStatements(selectMappings);
    }

    private void loadMapperXml(ProcessEngineConfigurationImpl config) {
        SqlSessionFactory sqlSessionFactory = config.getSqlSessionFactory();
        if (sqlSessionFactory == null) {
            throw new IllegalStateException("SqlSessionFactory 未初始化，无法装载自定义 Mapper");
        }
        org.apache.ibatis.session.Configuration configuration = sqlSessionFactory.getConfiguration();
        for (String resource : MAPPER_RESOURCES) {
            if (configuration.isResourceLoaded(resource)) {
                continue;
            }
            try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resource)) {
                if (inputStream == null) {
                    throw new IllegalStateException("找不到 Mapper 资源: " + resource);
                }
                new XMLMapperBuilder(inputStream, configuration, resource,
                        configuration.getSqlFragments()).parse();
            } catch (IOException e) {
                throw new IllegalStateException("装载 Mapper 失败: " + resource, e);
            }
        }
    }
}
