package ai.opencode.mcp.api;

import ai.opencode.mcp.annotation.Tool;

import java.util.Map;

/**
 * 承载注解工具在启动期扫描和远程端点绑定后的标准化定义。
 *
 * @param name 工具名称
 * @param title 工具展示标题
 * @param description 工具说明
 * @param inputSchema 工具输入 JSON Schema
 * @param invoker 工具调用器
 * @param hints 工具行为提示
 * @param type 启动绑定完成后的工具执行类别
 * @author beining.shang
 * @since 2026-08-31
 */
public record ToolRegistration(
    String name,
    String title,
    String description,
    Map<String, Object> inputSchema,
    ToolInvoker invoker,
    ToolHints hints,
    Tool.Type type) {

  /**
   * 创建并校验完整工具注册信息。
   *
   * @param name 工具名称，不能为空
   * @param title 工具展示标题
   * @param description 工具说明
   * @param inputSchema 工具输入 JSON Schema，不能为 {@code null}
   * @param invoker 工具调用器，不能为 {@code null}
   * @param hints 工具行为提示；为 {@code null} 时使用默认值
   * @param type 启动绑定完成后的工具执行类别，不能为 {@code null}
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
    if (type == null) {
      throw new IllegalArgumentException("Tool type must not be null");
    }
  }

  /**
   * 复制当前注册信息并替换工具执行类别。
   *
   * @param value 新的工具执行类别
   * @return 使用新执行类别的工具注册信息
   */
  public ToolRegistration withType(Tool.Type value) {
    return new ToolRegistration(name, title, description, inputSchema, invoker, hints, value);
  }

  /**
   * 复制当前注册信息并替换工具调用器。
   *
   * @param value 新的工具调用器
   * @return 使用新调用器的工具注册信息
   */
  public ToolRegistration withInvoker(ToolInvoker value) {
    return new ToolRegistration(name, title, description, inputSchema, value, hints, type);
  }
}
