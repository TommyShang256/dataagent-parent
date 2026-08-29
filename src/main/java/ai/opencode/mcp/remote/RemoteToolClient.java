package ai.opencode.mcp.remote;

import ai.opencode.mcp.api.ToolOrigin;
import ai.opencode.mcp.api.ToolHints;

import java.util.Collection;
import java.util.Map;

/** Common execution contract implemented by API Fabric and CSE/ServerComb clients. */
public interface RemoteToolClient {

  String id();

  ToolOrigin.Kind originKind();

  Collection<ToolDefinition> tools();

  Object execute(String toolName, Map<String, Object> arguments) throws Exception;

  record ToolDefinition(
      String name,
      String title,
      String description,
      Map<String, Object> inputSchema,
      ToolHints hints) {

    public ToolDefinition {
      if (name == null || name.isBlank()) throw new IllegalArgumentException("Remote tool name must not be blank");
      if (inputSchema == null) throw new IllegalArgumentException("Remote tool inputSchema must not be null");
      inputSchema = Map.copyOf(inputSchema);
      hints = hints == null ? ToolHints.DEFAULT : hints;
    }
  }

  @FunctionalInterface
  interface Executor {

    Object execute(String toolName, Map<String, Object> arguments) throws Exception;
  }
}
