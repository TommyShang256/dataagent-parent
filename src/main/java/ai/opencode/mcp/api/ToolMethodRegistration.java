package ai.opencode.mcp.api;

import java.lang.reflect.Method;

/**
 * 保留到端点绑定完成的注解方法元数据。
 *
 * @param method 注解工具对应的 Java 方法
 * @param registration 扫描阶段生成的工具注册信息
 */
public record ToolMethodRegistration(Method method, ToolRegistration registration) {}
