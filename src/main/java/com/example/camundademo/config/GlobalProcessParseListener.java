package com.example.camundademo.config;

import com.example.camundademo.listener.DeptCommCascadeListener;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.camunda.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.camunda.bpm.engine.impl.util.xml.Element;

/**
 * 全局流程解析监听器：给每个流程定义挂上“实例结束”监听器。
 *
 * <p>目前只承担一件事：主流程结束时级联终止它名下的部门沟通子流程。
 * 挂在解析期而不是逐个改 BPMN，是为了让所有既有流程（含以后新增的）自动具备该行为。</p>
 */
public class GlobalProcessParseListener extends AbstractBpmnParseListener {

    @Override
    public void parseProcess(Element processElement, ProcessDefinitionEntity processDefinition) {
        if (processDefinition == null) {
            return;
        }
        processDefinition.addListener(ExecutionListener.EVENTNAME_END, new DeptCommCascadeListener());
    }
}
