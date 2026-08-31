package ai.opencode.mcp.scanner;

import ai.opencode.mcp.api.ToolRegistration;
import java.lang.reflect.Method;

/** 保留到端点绑定完成的注解方法元数据。 */
public record ToolMethodRegistration(Method method, ToolRegistration registration) {}
