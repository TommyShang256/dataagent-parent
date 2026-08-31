package ai.opencode.mcp.remote;

import ai.opencode.mcp.api.ToolOrigin;
import org.springframework.web.reactive.function.client.WebClient;

/** 应用替换 API Fabric 和自定义 scheme CSE 客户端的扩展点。 */
@FunctionalInterface
public interface RemoteToolWebClientProvider {

  /**
   * 根据工具来源类别选择远程调用客户端。
   *
   * @param originKind 工具来源类别
   * @return 用于执行远程工具请求的 WebClient
   */
  WebClient webClient(ToolOrigin.Kind originKind);
}
