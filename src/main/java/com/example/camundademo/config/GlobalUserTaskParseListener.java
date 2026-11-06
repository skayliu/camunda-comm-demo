package com.example.camundademo.config;

import com.example.camundademo.listener.AgentDelegationFinalizeListener;
import com.example.camundademo.listener.CommunicationCompleteGuardListener;
import com.example.camundademo.listener.DelegationCompleteGuardListener;
import com.example.camundademo.listener.GlobalAgentTaskListener;
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
            // 顺序重要：代理先改派，TaskCreateInitListener 随后按最终 assignee 建扩展记录。
            // 反过来的话扩展表会存下代理前的旧办理人，且同一命令内查不到未 flush 的插入，
            // 补建会撞 UK_BIZ_TASK_EXT_TASK 唯一约束。
            taskDefinition.addTaskListener(TaskListener.EVENTNAME_CREATE, new GlobalAgentTaskListener());
            taskDefinition.addTaskListener(TaskListener.EVENTNAME_CREATE, new TaskCreateInitListener());
            // 候选人任务认领（claim）只触发 assignment，不会重新触发 create
            taskDefinition.addTaskListener(TaskListener.EVENTNAME_ASSIGNMENT, new GlobalAgentTaskListener());
            // complete 事件兜底：不依赖命令内部字段，拦截器反射失效时仍能挡住沟通中的审批
            taskDefinition.addTaskListener(TaskListener.EVENTNAME_COMPLETE,
                    new CommunicationCompleteGuardListener());
            // 委托中（PENDING）的任务只能由原办理人 complete，受托人只能 resolve
            taskDefinition.addTaskListener(TaskListener.EVENTNAME_COMPLETE,
                    new DelegationCompleteGuardListener());
            // 「可直接办结」的代理任务办结后，把委托记录收尾成 COMPLETED
            taskDefinition.addTaskListener(TaskListener.EVENTNAME_COMPLETE,
                    new AgentDelegationFinalizeListener());
        }
    }
}
