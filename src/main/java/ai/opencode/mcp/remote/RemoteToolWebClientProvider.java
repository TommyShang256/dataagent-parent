package ai.opencode.mcp.remote;

import ai.opencode.mcp.annotation.Tool;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 定义应用替换 API Fabric 和自定义 scheme CSE 客户端的扩展点。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
@FunctionalInterface
public interface RemoteToolWebClientProvider {

  /**
   * 根据工具执行类别选择远程调用客户端。
   *
   * @param type 工具执行类别
   * @return 用于执行远程工具请求的 WebClient
   */
  WebClient webClient(Tool.Type type);
}
