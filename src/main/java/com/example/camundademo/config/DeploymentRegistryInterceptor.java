package com.example.camundademo.config;

import org.camunda.bpm.engine.impl.cmd.DeployCmd;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.impl.interceptor.CommandInterceptor;
import org.camunda.bpm.engine.repository.Deployment;

/**
 * 部署命令拦截器：部署成功后把 deploymentId 交给
 * {@link DeptSubProcessDeploymentRegistrar}，由它在事务之外做注册表 upsert。
 *
 * <p>为什么只入队、不在命令链里直接写库：</p>
 * <ul>
 *   <li>命令返回时部署事务还没提交，此时用 JdbcTemplate 写注册表会落在另一个事务里；
 *       部署一旦回滚，注册表会留下指向不存在流程的脏记录；</li>
 *   <li>在命令链里再调用 RepositoryService 会嵌套命令，容易触发上下文复用问题。</li>
 * </ul>
 * <p>入队后由 Spring 侧在部署事务结束之后消费，顺序是确定的。</p>
 *
 * <p>注：Camunda 7 的 {@link CommandInterceptor} 只暴露 {@code execute} 一个扩展点，
 * 没有单独的 pre/post 钩子，所以这里在 {@code next.execute} 返回之后判断命令类型。
 * 拿到的 deploymentId 是引擎已分配的 ID，不依赖事务是否提交。</p>
 */
public class DeploymentRegistryInterceptor extends CommandInterceptor {

    @Override
    public <T> T execute(Command<T> command) {
        T result = next.execute(command);
        if (command instanceof DeployCmd && result instanceof Deployment deployment) {
            DeptSubProcessDeploymentRegistrar.markDeployed(deployment.getId());
        }
        return result;
    }
}
