package ai.opencode.mcp.remote;

import ai.opencode.mcp.api.ToolOrigin;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Remote tool client backed by API Fabric. */
public final class ApiFabricRemoteToolClient implements RemoteToolClient {

  private final String id;

  private final List<ToolDefinition> tools;

  private final Executor executor;

  public ApiFabricRemoteToolClient(String id, Collection<ToolDefinition> tools, Executor executor) {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("API Fabric client id must not be blank");
    if (tools == null) throw new IllegalArgumentException("API Fabric tools must not be null");
    if (executor == null) throw new IllegalArgumentException("API Fabric executor must not be null");
    this.id = id;
    this.tools = List.copyOf(tools);
    this.executor = executor;
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public ToolOrigin.Kind originKind() {
    return ToolOrigin.Kind.API_FABRIC;
  }

  @Override
  public Collection<ToolDefinition> tools() {
    return tools;
  }

  @Override
  public Object execute(String toolName, Map<String, Object> arguments) throws Exception {
    return executor.execute(toolName, arguments);
  }
}
