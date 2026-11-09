package com.example.camundademo.listener;

import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 部门专属子流程的「中间审批节点」完成监听器。
 *
 * <p>法务、财务这类专属流程的中间节点由固定岗位承接，填写的内容统一放在
 * {@code nodeOpinion} 变量里。这里按节点 key 收进 {@code opinionMap}，
 * 最后汇总节点就能看到完整链路意见，而不用为每个流程单独写一个监听器。</p>
 */
@Component("deptCommNodeOpinionListener")
public class DeptCommNodeOpinionListener implements TaskListener {

    private static final Logger log = LoggerFactory.getLogger(DeptCommNodeOpinionListener.class);

    @Override
    public void notify(DelegateTask task) {
        String opinion = (String) task.getVariable("nodeOpinion");
        String userId = task.getAssignee();

        Map<String, Object> opinionMap = mutableMap(task.getVariable("opinionMap"));
        Map<String, Object> item = new HashMap<>();
        item.put("userId", userId);
        item.put("nodeKey", task.getTaskDefinitionKey());
        item.put("nodeName", task.getName());
        item.put("opinion", opinion);
        item.put("submitTime", new Date());
        opinionMap.put(task.getTaskDefinitionKey(), item);
        task.setVariable("opinionMap", opinionMap);

        log.info("专属子流程节点提交：node={}, user={}", task.getTaskDefinitionKey(), userId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mutableMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new HashMap<>((Map<String, Object>) map);
        }
        return new HashMap<>();
    }
}
