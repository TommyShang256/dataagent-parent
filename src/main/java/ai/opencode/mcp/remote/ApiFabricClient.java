package ai.opencode.mcp.remote;

import ai.opencode.mcp.api.ToolOrigin;

public interface ApiFabricClient extends RemoteToolClient {

  @Override
  default ToolOrigin.Kind originKind() {
    return ToolOrigin.Kind.API_FABRIC;
  }
}
