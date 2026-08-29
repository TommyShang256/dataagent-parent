package ai.opencode.mcp.remote;

import ai.opencode.mcp.api.ToolHints;

import java.util.Map;

/** A remotely discovered tool before it is bound to a concrete remote client. */
public record RemoteToolDefinition(
    String name,
    String title,
    String description,
    Map<String, Object> inputSchema,
    ToolHints hints) {

  public RemoteToolDefinition {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("Remote tool name must not be blank");
    if (inputSchema == null) throw new IllegalArgumentException("Remote tool inputSchema must not be null");
    inputSchema = Map.copyOf(inputSchema);
    hints = hints == null ? ToolHints.DEFAULT : hints;
  }
}
