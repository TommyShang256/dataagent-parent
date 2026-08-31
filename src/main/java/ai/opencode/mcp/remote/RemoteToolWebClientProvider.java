package ai.opencode.mcp.remote;

import ai.opencode.mcp.api.ToolOrigin;
import org.springframework.web.reactive.function.client.WebClient;

/** 应用替换 API Fabric 和自定义 scheme CSE 客户端的扩展点。 */
@FunctionalInterface
public interface RemoteToolWebClientProvider {

  WebClient webClient(ToolOrigin.Kind originKind);
}
