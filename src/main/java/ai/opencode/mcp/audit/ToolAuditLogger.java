package ai.opencode.mcp.audit;

@FunctionalInterface
public interface ToolAuditLogger {

  void record(ToolAuditEvent event);
}
