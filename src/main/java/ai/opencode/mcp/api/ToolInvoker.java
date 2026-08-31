package ai.opencode.mcp.api;

import java.util.Map;

/** 执行一次标准化 MCP 工具调用。 */
@FunctionalInterface
public interface ToolInvoker {

  /**
   * 使用 Agent 提供的参数调用工具。
   *
   * @param arguments 工具参数映射
   * @return 工具执行结果
   * @throws Exception 工具执行失败时抛出
   */
  Object invoke(Map<String, Object> arguments) throws Exception;

  /**
   * 使用 Agent 参数及当前请求上下文调用工具。
   *
   * @param arguments 工具参数映射
   * @param context 当前请求的调用上下文
   * @return 工具执行结果
   * @throws Exception 工具执行失败时抛出
   */
  default Object invoke(Map<String, Object> arguments, ToolInvocationContext context) throws Exception {
    return invoke(arguments);
  }
}
