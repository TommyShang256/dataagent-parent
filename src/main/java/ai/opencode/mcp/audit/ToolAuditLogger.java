package ai.opencode.mcp.audit;

/**
 * 定义 MCP 工具注册与调用审计事件的记录契约。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
@FunctionalInterface
public interface ToolAuditLogger {

  /**
   * 记录一条工具审计事件。
   *
   * @param event 待记录的审计事件
   * 返回值：无。
   */
  void record(ToolAuditEvent event);
}
