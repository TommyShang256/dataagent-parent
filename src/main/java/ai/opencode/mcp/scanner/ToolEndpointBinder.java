package ai.opencode.mcp.scanner;

import ai.opencode.mcp.api.ToolMethodRegistration;
import ai.opencode.mcp.api.ToolRegistration;
import java.util.List;

/** 将注解方法注册编译为最终的启动期注册。 */
@FunctionalInterface
public interface ToolEndpointBinder {

  /** 不执行远程绑定、仅提取扫描注册信息的默认策略。 */
  ToolEndpointBinder LOCAL_ONLY = registrations -> registrations.stream()
      .map(ToolMethodRegistration::registration).toList();

  /**
   * 将扫描得到的注解方法编译为最终工具注册信息。
   *
   * @param registrations 扫描阶段的注解方法注册信息
   * @return 可发布到 MCP Server 的最终工具注册列表
   */
  List<ToolRegistration> bind(List<ToolMethodRegistration> registrations);
}
