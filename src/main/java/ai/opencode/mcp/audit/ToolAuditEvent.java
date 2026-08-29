package ai.opencode.mcp.audit;

import ai.opencode.mcp.api.ToolOrigin;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A complete MCP tool audit event, including invocation arguments and result. */
public record ToolAuditEvent(
    Instant timestamp,
    Operation operation,
    Outcome outcome,
    String toolName,
    ToolOrigin origin,
    Duration duration,
    Map<String, Object> arguments,
    Object result,
    String errorType) {

  public ToolAuditEvent {
    arguments = arguments == null
        ? null
        : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
  }

  public enum Operation {
    REGISTER,
    REMOVE,
    INVOKE
  }

  public enum Outcome {
    SUCCESS,
    FAILURE
  }
}
