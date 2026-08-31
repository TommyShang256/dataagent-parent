package ai.opencode.mcp.api;

import java.util.Map;

/** 本地扫描与远程发现共用的标准化启动期工具定义。 */
public record ToolRegistration(
    String name,
    String title,
    String description,
    Map<String, Object> inputSchema,
    ToolInvoker invoker,
    ToolHints hints,
    ToolOrigin origin) {

  public ToolRegistration {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("Tool name must not be blank");
    if (inputSchema == null) throw new IllegalArgumentException("Tool inputSchema must not be null");
    if (invoker == null) throw new IllegalArgumentException("Tool invoker must not be null");
    hints = hints == null ? ToolHints.DEFAULT : hints;
    origin = origin == null ? new ToolOrigin(ToolOrigin.Kind.CUSTOM, "programmatic") : origin;
  }

  public ToolRegistration(
      String name,
      String title,
      String description,
      Map<String, Object> inputSchema,
      ToolInvoker invoker,
      ToolHints hints) {
    this(name, title, description, inputSchema, invoker, hints, null);
  }

  public ToolRegistration withOrigin(ToolOrigin value) {
    return new ToolRegistration(name, title, description, inputSchema, invoker, hints, value);
  }

  public ToolRegistration withInvoker(ToolInvoker value) {
    return new ToolRegistration(name, title, description, inputSchema, value, hints, origin);
  }
}
