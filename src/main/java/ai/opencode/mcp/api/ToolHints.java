package ai.opencode.mcp.api;

/**
 * 描述 MCP 工具的行为提示。
 *
 * @param readOnly 工具是否只读
 * @param destructive 工具是否可能产生破坏性变更
 * @param idempotent 工具是否幂等
 * @param openWorld 工具是否可能访问外部实体
 */
public record ToolHints(boolean readOnly, boolean destructive, boolean idempotent, boolean openWorld) {

  /** 默认工具行为提示。 */
  public static final ToolHints DEFAULT = new ToolHints(false, true, false, true);
}
