package com.example.camundademo.config;

import com.example.camundademo.entity.BizCcRecord;
import com.example.camundademo.listener.CcProcessEndListener;
import com.example.camundademo.listener.CcSendExecutionListener;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.camunda.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.camunda.bpm.engine.impl.pvm.process.ActivityImpl;
import org.camunda.bpm.engine.impl.pvm.process.ScopeImpl;
import org.camunda.bpm.engine.impl.util.xml.Element;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局传阅解析监听器：把 BPMN 里声明的静态待阅人翻译成节点/流程结束监听器。
 *
 * <p>配置写在标准 {@code camunda:properties} 扩展字段里，流程文件不需要画任何传阅节点：</p>
 * <pre>{@code
 * <userTask id="managerApproval" name="经理审批">
 *   <extensionElements>
 *     <camunda:properties>
 *       <camunda:property name="ccUsers" value="zhangsan,lisi"/>
 *       <camunda:property name="ccType" value="AFTER_NODE"/>
 *     </camunda:properties>
 *   </extensionElements>
 * </userTask>
 * }</pre>
 *
 * <p>默认在<b>节点结束之后</b>发送：节点刚进审批就传阅，待阅人点进去看到“审批中”，
 * 之后驳回还会再收到一次，体验很差。</p>
 */
public class GlobalCcParseListener extends AbstractBpmnParseListener {

    private static final String PROP_CC_USERS = "ccUsers";
    private static final String PROP_CC_TYPE = "ccType";

    @Override
    public void parseUserTask(Element userTaskElement, ScopeImpl scope, ActivityImpl activity) {
        Map<String, String> props = readProperties(userTaskElement);
        String users = props.get(PROP_CC_USERS);
        if (isBlank(users) || activity == null) {
            return;
        }
        String type = props.getOrDefault(PROP_CC_TYPE, BizCcRecord.TYPE_NODE);
        // 归一化：BPMN 里写 AFTER_NODE 与 NODE 等价
        if ("AFTER_NODE".equalsIgnoreCase(type)) {
            type = BizCcRecord.TYPE_NODE;
        }
        activity.addListener(ExecutionListener.EVENTNAME_END, new CcSendExecutionListener(users, type));
    }

    @Override
    public void parseProcess(Element processElement, ProcessDefinitionEntity processDefinition) {
        if (processDefinition == null) {
            return;
        }
        // 流程级传阅：整套流程结束时统一传阅存档，不需要每个节点重复配置
        Map<String, String> props = readProperties(processElement);
        String users = props.get(PROP_CC_USERS);
        if (!isBlank(users)) {
            processDefinition.addListener(ExecutionListener.EVENTNAME_END,
                    new CcSendExecutionListener(users, BizCcRecord.TYPE_INST));
        }
        // 流程结束/撤销：未完成阅办失效；被撤销时未读传阅同时失效
        processDefinition.addListener(ExecutionListener.EVENTNAME_END, new CcProcessEndListener());
    }

    /**
     * 读取 extensionElements → camunda:properties → camunda:property 的键值。
     * 只取纯文本属性，不依赖具体建模器。
     */
    private Map<String, String> readProperties(Element element) {
        Map<String, String> props = new LinkedHashMap<>();
        Element extensionElements = element.element("extensionElements");
        if (extensionElements == null) {
            return props;
        }
        for (Element properties : extensionElements.elements("properties")) {
            for (Element property : properties.elements("property")) {
                String name = property.attribute("name");
                String value = property.attribute("value");
                if (!isBlank(name)) {
                    props.put(name.trim(), value);
                }
            }
        }
        return props;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
