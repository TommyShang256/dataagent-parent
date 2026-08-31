package ai.opencode.mcp.audit;

import lombok.extern.slf4j.Slf4j;

/** 使用 SLF4J 参数化日志记录完整的 MCP 工具审计事件。 */
@Slf4j(topic = "ai.opencode.mcp.audit.tool")
public final class Slf4jToolAuditLogger implements ToolAuditLogger {

  public static final String LOGGER_NAME = "ai.opencode.mcp.audit.tool";

  @Override
  public void record(ToolAuditEvent event) {
    var message = "MCP tool audit operation={} outcome={} tool={} origin={} source={} durationMs={} "
        + "arguments={} result={} errorType={}";
    var arguments = new Object[] {
      event.operation(),
      event.outcome(),
      event.toolName(),
      event.origin().kind(),
      event.origin().sourceId(),
      event.duration().toMillis(),
      event.arguments(),
      event.result(),
      event.errorType()
    };
    if (event.outcome() == ToolAuditEvent.Outcome.SUCCESS) {
      log.info(message, arguments);
      return;
    }
    log.warn(message, arguments);
  }
}
