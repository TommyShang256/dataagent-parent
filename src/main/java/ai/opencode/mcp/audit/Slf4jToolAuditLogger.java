package ai.opencode.mcp.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Writes complete MCP tool audit events as SLF4J parameterized logs. */
public final class Slf4jToolAuditLogger implements ToolAuditLogger {

  public static final String LOGGER_NAME = "ai.opencode.mcp.audit.tool";

  private final Logger logger = LoggerFactory.getLogger(LOGGER_NAME);

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
      logger.info(message, arguments);
      return;
    }
    logger.warn(message, arguments);
  }
}
