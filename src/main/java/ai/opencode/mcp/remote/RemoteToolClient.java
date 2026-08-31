package ai.opencode.mcp.remote;

import ai.opencode.mcp.api.ToolOrigin;
import ai.opencode.mcp.api.ToolHints;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 启动期固定的通用远程 MCP 工具来源。 */
public interface RemoteToolClient {

  /**
   * 使用固定工具定义与执行器创建通用远程工具客户端。
   *
   * @param id 客户端来源标识
   * @param originKind 工具来源类别
   * @param tools 启动期固定的工具定义
   * @param executor 工具执行器
   * @return 通用远程工具客户端
   */
  static RemoteToolClient of(
      String id,
      ToolOrigin.Kind originKind,
      Collection<ToolDefinition> tools,
      Executor executor) {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("Remote tool client id must not be blank");
    }
    if (originKind == null) {
      throw new IllegalArgumentException("Remote tool origin kind must not be null");
    }
    if (tools == null) {
      throw new IllegalArgumentException("Remote tools must not be null");
    }
    if (executor == null) {
      throw new IllegalArgumentException("Remote tool executor must not be null");
    }
    List<ToolDefinition> definitions = List.copyOf(tools);
    return new RemoteToolClient() {
      /**
       * 获取客户端来源标识。
       *
       * @return 客户端来源标识
       */
      @Override
      public String id() {
        return id;
      }

      /**
       * 获取工具来源类别。
       *
       * @return 工具来源类别
       */
      @Override
      public ToolOrigin.Kind originKind() {
        return originKind;
      }

      /**
       * 获取启动期固定的工具定义。
       *
       * @return 不可变工具定义集合
       */
      @Override
      public Collection<ToolDefinition> tools() {
        return definitions;
      }

      /**
       * 将工具调用委托给创建客户端时提供的执行器。
       *
       * @param toolName 工具名称
       * @param arguments 工具参数
       * @return 工具执行结果
       * @throws Exception 执行器调用失败时抛出
       */
      @Override
      public Object execute(String toolName, Map<String, Object> arguments) throws Exception {
        return executor.execute(toolName, arguments);
      }
    };
  }

  /**
   * 获取客户端来源标识。
   *
   * @return 客户端来源标识
   */
  String id();

  /**
   * 获取客户端发现工具的来源类别。
   *
   * @return 工具来源类别
   */
  ToolOrigin.Kind originKind();

  /**
   * 获取启动期固定的远程工具定义。
   *
   * @return 远程工具定义集合
   */
  Collection<ToolDefinition> tools();

  /**
   * 执行指定远程工具。
   *
   * @param toolName 工具名称
   * @param arguments 工具参数
   * @return 工具执行结果
   * @throws Exception 远程工具执行失败时抛出
   */
  Object execute(String toolName, Map<String, Object> arguments) throws Exception;

  /**
   * 描述一个由远程客户端提供的工具。
   *
   * @param name 工具名称
   * @param title 工具展示标题
   * @param description 工具说明
   * @param inputSchema 工具输入 JSON Schema
   * @param hints 工具行为提示
   */
  record ToolDefinition(
      String name,
      String title,
      String description,
      Map<String, Object> inputSchema,
      ToolHints hints) {

    /**
     * 创建并校验远程工具定义。
     *
     * @param name 工具名称，不能为空
     * @param title 工具展示标题
     * @param description 工具说明
     * @param inputSchema 工具输入 JSON Schema，不能为 {@code null}
     * @param hints 工具行为提示；为 {@code null} 时使用默认值
     */
    public ToolDefinition {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("Remote tool name must not be blank");
      }
      if (inputSchema == null) {
        throw new IllegalArgumentException("Remote tool inputSchema must not be null");
      }
      inputSchema = Map.copyOf(inputSchema);
      hints = hints == null ? ToolHints.DEFAULT : hints;
    }
  }

  /** 执行由通用远程客户端提供的工具。 */
  @FunctionalInterface
  interface Executor {

    /**
     * 执行指定远程工具。
     *
     * @param toolName 工具名称
     * @param arguments 工具参数
     * @return 工具执行结果
     * @throws Exception 工具执行失败时抛出
     */
    Object execute(String toolName, Map<String, Object> arguments) throws Exception;
  }
}
