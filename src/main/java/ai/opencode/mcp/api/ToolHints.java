package ai.opencode.mcp.api;

public record ToolHints(boolean readOnly, boolean destructive, boolean idempotent, boolean openWorld) {

  public static final ToolHints DEFAULT = new ToolHints(false, true, false, true);
}
