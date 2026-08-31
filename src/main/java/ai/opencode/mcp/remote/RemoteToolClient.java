package ai.opencode.mcp.remote;

import ai.opencode.mcp.api.ToolOrigin;
import ai.opencode.mcp.api.ToolHints;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 启动期固定的通用远程 MCP 工具来源。 */
public interface RemoteToolClient {

  static RemoteToolClient of(
      String id,
      ToolOrigin.Kind originKind,
      Collection<ToolDefinition> tools,
      Executor executor) {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("Remote tool client id must not be blank");
    if (originKind == null) throw new IllegalArgumentException("Remote tool origin kind must not be null");
    if (tools == null) throw new IllegalArgumentException("Remote tools must not be null");
    if (executor == null) throw new IllegalArgumentException("Remote tool executor must not be null");
    var definitions = List.copyOf(tools);
    return new RemoteToolClient() {
      @Override
      public String id() {
        return id;
      }

      @Override
      public ToolOrigin.Kind originKind() {
        return originKind;
      }

      @Override
      public Collection<ToolDefinition> tools() {
        return definitions;
      }

      @Override
      public Object execute(String toolName, Map<String, Object> arguments) throws Exception {
        return executor.execute(toolName, arguments);
      }
    };
  }

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
