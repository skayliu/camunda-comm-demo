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
 * 通用子流程的「经办人意见」完成监听器。
 *
 * <p>经办意见写入 {@code opinionMap}（key = 用户ID），文书在汇总节点据此形成
 * 部门正式口径。单个经办人意见不能直接当部门意见对外反馈，必须经文书汇总。</p>
 */
@Component("deptCommHandlerListener")
public class DeptCommHandlerListener implements TaskListener {

    private static final Logger log = LoggerFactory.getLogger(DeptCommHandlerListener.class);

    @Override
    public void notify(DelegateTask task) {
        String userId = task.getAssignee() != null
                ? task.getAssignee() : (String) task.getVariable("handlerUserId");
        String opinion = (String) task.getVariable("opinionContent");
        Object attachments = task.getVariable("attachments");
        Boolean returnToClerk = (Boolean) task.getVariable("returnToClerk");

        Map<String, Object> opinionMap = mutableMap(task.getVariable("opinionMap"));
        Map<String, Object> item = new HashMap<>();
        item.put("userId", userId);
        item.put("opinion", opinion);
        item.put("attachments", attachments);
        item.put("submitTime", new Date());
        item.put("returnToClerk", Boolean.TRUE.equals(returnToClerk));
        if (userId != null) {
            opinionMap.put(userId, item);
        }
        task.setVariable("opinionMap", opinionMap);

        log.info("经办人提交意见：task={}, user={}, returnToClerk={}",
                task.getId(), userId, returnToClerk);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mutableMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new HashMap<>((Map<String, Object>) map);
        }
        return new HashMap<>();
    }
}
