package ai.opencode.mcp.remote;

import ai.opencode.mcp.api.ToolOrigin;

/** Internal CSE client; ServerComb is the corresponding external name. */
public interface CseClient extends RemoteToolClient {

  @Override
  default ToolOrigin.Kind originKind() {
    return ToolOrigin.Kind.SERVER_COMB;
  }
}
