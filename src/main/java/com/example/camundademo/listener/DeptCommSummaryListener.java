package com.example.camundademo.listener;

import com.example.camundademo.service.BusinessException;
import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 部门子流程的「文书汇总」节点完成监听器（通用流程的汇总节点、专属流程的最终意见节点共用）。
 *
 * <p>汇总节点有三种操作：提交最终意见、退回经办人补充、重新分发。
 * 这里负责把操作翻译成流程变量，网关据此路由；同时校验各分支的必填项。</p>
 */
@Component("deptCommSummaryListener")
public class DeptCommSummaryListener implements TaskListener {

    private static final Logger log = LoggerFactory.getLogger(DeptCommSummaryListener.class);

    @Override
    public void notify(DelegateTask task) {
        String finalOpinion = (String) task.getVariable("finalOpinion");
        String actionType = (String) task.getVariable("actionType");

        // 专属流程的汇总节点没有 actionType（固定线性流转），按 SUBMIT 处理
        if (actionType == null || actionType.isBlank()) {
            actionType = "SUBMIT";
            task.setVariable("actionType", actionType);
        }

        if ("SUBMIT".equals(actionType) && (finalOpinion == null || finalOpinion.isBlank())) {
            throw new BusinessException("请填写部门最终汇总意见后再提交");
        }
        if ("RETURN_SUPPLEMENT".equals(actionType)) {
            List<String> returnIds = readList(task.getVariable("returnHandlerIds"));
            if (returnIds.isEmpty()) {
                throw new BusinessException("退回补充时必须指定需要补充意见的经办人");
            }
            // 回到经办节点时只给被指定的人生成待办：覆盖多实例集合，旧意见仍保留在 opinionMap
            task.setVariable("handlerUserIds", returnIds);
        }
        if (!"SUBMIT".equals(actionType)) {
            log.info("部门沟通汇总未提交：task={}, action={}", task.getId(), actionType);
        }
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
