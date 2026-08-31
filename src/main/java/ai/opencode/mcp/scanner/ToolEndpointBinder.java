package ai.opencode.mcp.scanner;

import ai.opencode.mcp.api.ToolRegistration;
import java.util.List;

/** 将注解方法注册编译为最终的启动期注册。 */
@FunctionalInterface
public interface ToolEndpointBinder {

  ToolEndpointBinder LOCAL_ONLY = registrations -> registrations.stream()
      .map(ToolMethodRegistration::registration).toList();

  List<ToolRegistration> bind(List<ToolMethodRegistration> registrations);
}
