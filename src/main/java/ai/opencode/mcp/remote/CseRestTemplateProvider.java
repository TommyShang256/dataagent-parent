package ai.opencode.mcp.remote;

import org.springframework.web.client.RestOperations;

/**
 * 提供公司运行环境中支持 {@code cse://} 协议的 RestTemplate 调用实现。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
@FunctionalInterface
public interface CseRestTemplateProvider {

  /**
   * 获取支持 CSE 服务发现和调用的 RestTemplate 操作接口。
   *
   * @return 支持 {@code cse://} URI 的 RestTemplate 操作接口
   */
  RestOperations restOperations();
}
