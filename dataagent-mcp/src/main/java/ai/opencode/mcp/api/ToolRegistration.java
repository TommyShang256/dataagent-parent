package ai.opencode.mcp.api;

import ai.opencode.mcp.annotation.Tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 承载注解工具在启动期扫描和远程端点绑定后的标准化定义。
 *
 * @param name 工具名称
 * @param definition 工具展示与输入定义
 * @param invoker 工具调用器
 * @param type 启动绑定完成后的工具执行类别
 * @param behavior 工具行为属性
 * @author beining.shang
 * @since 2026-08-31
 */
public record ToolRegistration(
    String name,
    Definition definition,
    ToolInvoker invoker,
    Tool.Type type,
    Behavior behavior) {

  /**
   * 创建并校验完整工具注册信息。
   *
   * @param name 工具名称，不能为空
   * @param definition 工具展示与输入定义，不能为 {@code null}
   * @param invoker 工具调用器，不能为 {@code null}
   * @param type 启动绑定完成后的工具执行类别，不能为 {@code null}
   * @param behavior 工具行为属性，不能为 {@code null}
   */
  public ToolRegistration {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Tool name must not be blank");
    }
    if (definition == null) {
      throw new IllegalArgumentException("Tool definition must not be null");
    }
    if (invoker == null) {
      throw new IllegalArgumentException("Tool invoker must not be null");
    }
    if (type == null) {
      throw new IllegalArgumentException("Tool type must not be null");
    }
    if (behavior == null) {
      throw new IllegalArgumentException("Tool behavior must not be null");
    }
  }

  /**
   * 获取工具展示标题。
   *
   * @return 工具展示标题
   */
  public String title() {
    return definition.title;
  }

  /**
   * 获取工具说明。
   *
   * @return 工具说明
   */
  public String description() {
    return definition.description;
  }

  /**
   * 获取工具输入 JSON Schema。
   *
   * @return 不可变工具输入 JSON Schema
   */
  public Map<String, Object> inputSchema() {
    return definition.inputSchema;
  }

  /**
   * 判断工具是否只读。
   *
   * @return 工具只读时返回 {@code true}
   */
  public boolean readOnly() {
    return behavior.readOnly;
  }

  /**
   * 判断工具是否可能产生破坏性变更。
   *
   * @return 工具可能产生破坏性变更时返回 {@code true}
   */
  public boolean destructive() {
    return behavior.destructive;
  }

  /**
   * 判断工具是否幂等。
   *
   * @return 工具幂等时返回 {@code true}
   */
  public boolean idempotent() {
    return behavior.idempotent;
  }

  /**
   * 判断工具是否可能访问外部实体。
   *
   * @return 工具可能访问外部实体时返回 {@code true}
   */
  public boolean openWorld() {
    return behavior.openWorld;
  }

  /**
   * 复制当前注册信息并替换工具执行类别。
   *
   * @param value 新的工具执行类别
   * @return 使用新执行类别的工具注册信息
   */
  public ToolRegistration withType(Tool.Type value) {
    return new ToolRegistration(name, definition, invoker, value, behavior);
  }

  /**
   * 复制当前注册信息并替换工具调用器。
   *
   * @param value 新的工具调用器
   * @return 使用新调用器的工具注册信息
   */
  public ToolRegistration withInvoker(ToolInvoker value) {
    return new ToolRegistration(name, definition, value, type, behavior);
  }

  /**
   * 保存工具展示信息与输入 Schema。
   */
  public static final class Definition {

    private final String title;
    private final String description;
    private final Map<String, Object> inputSchema;

    /**
     * 创建并校验工具定义。
     *
     * @param title 工具展示标题
     * @param description 工具说明
     * @param inputSchema 工具输入 JSON Schema
     */
    public Definition(String title, String description, Map<String, Object> inputSchema) {
      if (inputSchema == null) {
        throw new IllegalArgumentException("Tool inputSchema must not be null");
      }
      this.title = title;
      this.description = description;
      this.inputSchema = Collections.unmodifiableMap(new LinkedHashMap<>(inputSchema));
    }
  }

  /**
   * 保存 MCP 工具行为属性。
   */
  public static final class Behavior {

    private final boolean readOnly;
    private final boolean destructive;
    private final boolean idempotent;
    private final boolean openWorld;

    /**
     * 创建工具行为属性。
     *
     * @param readOnly 工具是否只读
     * @param destructive 工具是否可能产生破坏性变更
     * @param idempotent 工具是否幂等
     * @param openWorld 工具是否可能访问外部实体
     */
    public Behavior(boolean readOnly, boolean destructive, boolean idempotent, boolean openWorld) {
      this.readOnly = readOnly;
      this.destructive = destructive;
      this.idempotent = idempotent;
      this.openWorld = openWorld;
    }
  }
}
