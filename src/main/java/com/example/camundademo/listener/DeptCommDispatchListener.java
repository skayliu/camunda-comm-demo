package com.example.camundademo.listener;

import com.example.camundademo.service.BusinessException;
import com.example.camundademo.service.DeptCommProcessService;
import com.example.camundademo.service.SubProcessDefService;
import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 通用子流程的「文书分发」节点完成监听器。
 *
 * <p>硬校验经办人必须属于被征询部门：只靠前端下拉框限制不住直接调接口，
 * 跨部门选人会让敏感沟通落到无关人员手里。</p>
 */
@Component("deptCommDispatchListener")
public class DeptCommDispatchListener implements TaskListener {

    private static final Logger log = LoggerFactory.getLogger(DeptCommDispatchListener.class);

    @Autowired
    private SubProcessDefService subProcessDefService;

    @Override
    public void notify(DelegateTask task) {
        String deptId = (String) task.getVariable(DeptCommProcessService.VAR_DEPT_ID);
        List<String> handlerIds = readList(task.getVariable("handlerUserIds"));
        if (handlerIds.isEmpty()) {
            throw new BusinessException("请至少选择一名经办人");
        }
        for (String userId : handlerIds) {
            if (!subProcessDefService.isDeptMember(userId, deptId)) {
                throw new BusinessException("经办人 " + userId + " 不属于本部门，不能分发给他");
            }
        }
        String rule = (String) task.getVariable("opinionRule");
        if (rule == null || rule.isBlank()) {
            task.setVariable("opinionRule", "ALL");
        }
        // 上一轮可能因“经办人退回”把 returnToClerk 置为 true；分发时必须复位，
        // 否则新一轮经办人一提交就会被网关误判为再次退回，形成死循环。
        task.setVariable("returnToClerk", false);
        log.info("部门沟通分发：dept={}, handlers={}, rule={}",
                deptId, handlerIds, rule == null ? "ALL" : rule);
    }

    private List<String> readList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof Collection<?> c) {
            for (Object o : c) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    result.add(String.valueOf(o));
                }
            }
        } else if (value instanceof String s && !s.isBlank()) {
            for (String part : s.split(",")) {
                if (!part.isBlank()) {
                    result.add(part.trim());
                }
            }
        }
        return result;
    }
}
