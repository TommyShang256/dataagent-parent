package ai.opencode.mcp.api;

import java.util.Map;

/**
 * 本地扫描与远程发现共用的标准化启动期工具定义。
 *
 * @param name 工具名称
 * @param title 工具展示标题
 * @param description 工具说明
 * @param inputSchema 工具输入 JSON Schema
 * @param invoker 工具调用器
 * @param hints 工具行为提示
 * @param origin 工具来源
 */
public record ToolRegistration(
    String name,
    String title,
    String description,
    Map<String, Object> inputSchema,
    ToolInvoker invoker,
    ToolHints hints,
    ToolOrigin origin) {

  /**
   * 创建并校验完整工具注册信息。
   *
   * @param name 工具名称，不能为空
   * @param title 工具展示标题
   * @param description 工具说明
   * @param inputSchema 工具输入 JSON Schema，不能为 {@code null}
   * @param invoker 工具调用器，不能为 {@code null}
   * @param hints 工具行为提示；为 {@code null} 时使用默认值
   * @param origin 工具来源；为 {@code null} 时使用编程式来源
   */
  public ToolRegistration {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Tool name must not be blank");
    }
    if (inputSchema == null) {
      throw new IllegalArgumentException("Tool inputSchema must not be null");
    }
    if (invoker == null) {
      throw new IllegalArgumentException("Tool invoker must not be null");
    }
    hints = hints == null ? ToolHints.DEFAULT : hints;
    origin = origin == null ? new ToolOrigin(ToolOrigin.Kind.CUSTOM, "programmatic") : origin;
  }

  /**
   * 创建未显式指定来源的工具注册信息。
   *
   * @param name 工具名称
   * @param title 工具展示标题
   * @param description 工具说明
   * @param inputSchema 工具输入 JSON Schema
   * @param invoker 工具调用器
   * @param hints 工具行为提示
   */
  public ToolRegistration(
      String name,
      String title,
      String description,
      Map<String, Object> inputSchema,
      ToolInvoker invoker,
      ToolHints hints) {
    this(name, title, description, inputSchema, invoker, hints, null);
  }

  /**
   * 复制当前注册信息并替换工具来源。
   *
   * @param value 新的工具来源
   * @return 使用新来源的工具注册信息
   */
  public ToolRegistration withOrigin(ToolOrigin value) {
    return new ToolRegistration(name, title, description, inputSchema, invoker, hints, value);
  }

  /**
   * 复制当前注册信息并替换工具调用器。
   *
   * @param value 新的工具调用器
   * @return 使用新调用器的工具注册信息
   */
  public ToolRegistration withInvoker(ToolInvoker value) {
    return new ToolRegistration(name, title, description, inputSchema, value, hints, origin);
  }
}
