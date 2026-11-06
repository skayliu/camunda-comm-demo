package com.example.camundademo.interceptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从 Camunda 命令对象里取 taskId。
 *
 * <p>为什么需要反射：{@code CompleteTaskCmd} / {@code SubmitTaskFormCmd} 的内部字段
 * {@code taskId} 没有公开 getter，而拦截器需要它在 {@code execute} 之前做校验。</p>
 *
 * <p>这里做了三件事，避免“跨版本一升级就静默失效”：</p>
 * <ol>
 *   <li><b>缓存 Field</b>：按命令类缓存查找结果，避免每条命令都遍历类层次；</li>
 *   <li><b>多路径读取</b>：先找字段（含父类），再退到 {@code getTaskId()}；</li>
 *   <li><b>可测试</b>：解析逻辑独立成类，不依赖引擎上下文，单测可直接构造命令验证。</li>
 * </ol>
 *
 * <p>注意：解析失败返回 {@code null} 而不是抛异常。拦截器据此放行，
 * 由 {@code CommunicationCompleteGuardListener} 在任务 complete 事件上兜底，
 * 因此这里的失败不会变成“沟通中的待办被放行”。</p>
 */
public final class CommandTaskId {

    /** 空 Optional 表示“该类及其父类都没有 taskId 字段”，用于跳过重复扫描 */
    private static final Map<Class<?>, Optional<Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    private static final String FIELD_NAME = "taskId";
    private static final String GETTER_NAME = "getTaskId";

    private CommandTaskId() {
    }

    /**
     * @return 命令携带的任务 ID；无法解析时返回 {@code null}
     */
    public static String resolve(Object command) {
        if (command == null) {
            return null;
        }
        Object byField = readField(command);
        if (byField instanceof String && !((String) byField).isEmpty()) {
            return (String) byField;
        }
        Object byGetter = readGetter(command);
        if (byGetter instanceof String && !((String) byGetter).isEmpty()) {
            return (String) byGetter;
        }
        return null;
    }

    private static Object readField(Object command) {
        Class<?> clazz = command.getClass();
        Optional<Field> cached = FIELD_CACHE.get(clazz);
        if (cached == null) {
            cached = Optional.ofNullable(findField(clazz));
            FIELD_CACHE.put(clazz, cached);
        }
        if (!cached.isPresent()) {
            return null;
        }
        try {
            return cached.get().get(command);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 字段存在但读不出来（安全策略/类型变化）：不缓存失败结果，下次仍可重试
            return null;
        }
    }

    /** 沿继承链向上找，命中第一个 taskId 字段（Camunda 把字段放在具体命令或抽象父类上都兼容） */
    private static Field findField(Class<?> clazz) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(FIELD_NAME);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private static Object readGetter(Object command) {
        try {
            Method method = command.getClass().getMethod(GETTER_NAME);
            return method.invoke(command);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
