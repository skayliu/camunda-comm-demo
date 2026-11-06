package com.example.camundademo.config;

import com.example.camundademo.listener.CommunicationCompleteGuardListener;
import com.example.camundademo.listener.TaskCreateInitListener;
import com.example.camundademo.persistence.BizCommSessionManager;
import com.example.camundademo.persistence.BizCommTaskManager;
import com.example.camundademo.persistence.BizTaskExtManager;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.camunda.bpm.engine.impl.bpmn.behavior.UserTaskActivityBehavior;
import org.camunda.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.camunda.bpm.engine.impl.pvm.process.ActivityImpl;
import org.camunda.bpm.engine.impl.pvm.process.ScopeImpl;
import org.camunda.bpm.engine.impl.task.TaskDefinition;
import org.camunda.bpm.engine.impl.util.xml.Element;

/**
 * 全局BPMN解析监听器：给所有UserTask自动注入创建监听器与完成前兜底校验，
 * 模型文件零改动。
 */
public class GlobalUserTaskParseListener extends AbstractBpmnParseListener {

    @Override
    public void parseUserTask(Element userTaskElement, ScopeImpl scope, ActivityImpl activity) {
        UserTaskActivityBehavior behavior = (UserTaskActivityBehavior) activity.getActivityBehavior();
        TaskDefinition taskDefinition = behavior.getTaskDefinition();
        if (taskDefinition != null) {
            taskDefinition.addTaskListener(TaskListener.EVENTNAME_CREATE, new TaskCreateInitListener());
            // complete 事件兜底：不依赖命令内部字段，拦截器反射失效时仍能挡住沟通中的审批
            taskDefinition.addTaskListener(TaskListener.EVENTNAME_COMPLETE,
                    new CommunicationCompleteGuardListener());
        }
    }
}
