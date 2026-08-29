package ai.opencode.mcp.remote;

import ai.opencode.mcp.api.ToolOrigin;

import java.util.Collection;
import java.util.Map;

/** Common execution contract implemented by API Fabric and CSE/ServerComb clients. */
public interface RemoteToolClient {

  String id();

  ToolOrigin.Kind originKind();

  Collection<RemoteToolDefinition> tools();

  Object execute(String toolName, Map<String, Object> arguments) throws Exception;
}
