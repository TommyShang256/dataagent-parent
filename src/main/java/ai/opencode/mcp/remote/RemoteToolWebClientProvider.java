package ai.opencode.mcp.remote;

import ai.opencode.mcp.api.ToolOrigin;
import org.springframework.web.reactive.function.client.WebClient;

/** Application replacement point for API Fabric and custom-scheme CSE clients. */
@FunctionalInterface
public interface RemoteToolWebClientProvider {

  WebClient webClient(ToolOrigin.Kind originKind);
}
