package com.example.camundademo;

import com.example.camundademo.interceptor.CommandTaskId;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link CommandTaskId} 的单测。
 *
 * <p>反射读 taskId 是拦截器最脆弱的一环：Camunda 换个大版本把字段挪走或改名，
 * 生产表现是“沟通中的待办突然能审批了”，而且不会报错。这里把几种真实结构都固定成用例，
 * 至少升级后跑一次测试就能发现问题。</p>
 */
class CommandTaskIdTest {

    // ===== 测试替身：模拟 Camunda 的两种字段位置 =====

    /** 字段在具体命令类上（CompleteTaskCmd 就是这种） */
    static class FieldOnCommand {
        private final String taskId;

        FieldOnCommand(String taskId) {
            this.taskId = taskId;
        }
    }

    /** 字段在抽象父类上（SubmitTaskFormCmd 继承链就是这种） */
    static abstract class AbstractCompleteLikeCmd {
        protected String taskId;

        AbstractCompleteLikeCmd(String taskId) {
            this.taskId = taskId;
        }
    }

    static class FieldOnSuperclass extends AbstractCompleteLikeCmd {
        FieldOnSuperclass(String taskId) {
            super(taskId);
        }
    }

    /** 没有字段，只有 getter：未来 Camunda 若补了 getter，这里应能直接读到 */
    static class GetterOnly {
        private final String taskId;

        GetterOnly(String taskId) {
            this.taskId = taskId;
        }

        public String getTaskId() {
            return taskId;
        }
    }

    static class NoTaskId {
    }

    // ===== 用例 =====

    @Test
    void resolvesFieldOnCommandClass() {
        assertEquals("task-1", CommandTaskId.resolve(new FieldOnCommand("task-1")));
    }

    @Test
    void resolvesFieldOnSuperclass() {
        assertEquals("task-2", CommandTaskId.resolve(new FieldOnSuperclass("task-2")));
    }

    @Test
    void resolvesGetterWhenNoField() {
        assertEquals("task-3", CommandTaskId.resolve(new GetterOnly("task-3")));
    }

    @Test
    void returnsNullWhenCommandCarriesNoTaskId() {
        assertNull(CommandTaskId.resolve(new NoTaskId()));
    }

    @Test
    void returnsNullForNullOrEmptyTaskId() {
        assertNull(CommandTaskId.resolve(null));
        assertNull(CommandTaskId.resolve(new FieldOnCommand("")));
        assertNull(CommandTaskId.resolve(new FieldOnCommand(null)));
    }

    // ===== 真实引擎命令：防止升级后字段位置变化 =====

    @Test
    void resolvesRealCompleteTaskCmd() throws Exception {
        Object cmd = instantiate("org.camunda.bpm.engine.impl.cmd.CompleteTaskCmd", "real-task-1");
        assertNotNull(cmd, "应能构造 CompleteTaskCmd");
        assertEquals("real-task-1", CommandTaskId.resolve(cmd));
    }

    @Test
    void resolvesRealSubmitTaskFormCmd() throws Exception {
        Object cmd = instantiate("org.camunda.bpm.engine.impl.cmd.SubmitTaskFormCmd", "real-task-2");
        assertNotNull(cmd, "应能构造 SubmitTaskFormCmd");
        assertEquals("real-task-2", CommandTaskId.resolve(cmd));
    }

    /**
     * 按“第一个参数是 taskId”的约定构造命令，其余参数填默认值。
     * 这样不把构造器签名写死：Camunda 增删可选参数时测试仍可编译。
     */
    private static Object instantiate(String className, String taskId) throws Exception {
        Class<?> clazz = Class.forName(className);
        for (Constructor<?> c : clazz.getConstructors()) {
            Class<?>[] types = c.getParameterTypes();
            if (types.length >= 1 && types[0] == String.class) {
                Object[] args = new Object[types.length];
                args[0] = taskId;
                for (int i = 1; i < types.length; i++) {
                    args[i] = defaultValue(types[i]);
                }
                return c.newInstance(args);
            }
        }
        return null;
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (Map.class.isAssignableFrom(type)) {
            return new HashMap<String, Object>();
        }
        return null;
    }
}
