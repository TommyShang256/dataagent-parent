package ai.opencode.mcp.remote;

import ai.opencode.mcp.annotation.Tool;
import ai.opencode.mcp.api.ToolRegistration;
import ai.opencode.mcp.autoconfigure.McpFabricProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.web.reactive.function.client.WebClient;

/**
 * 保留完整 {@code cse://} URI 并绑定 CSE 远程工具端点。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
public final class CseToolEndpointHandler implements RemoteToolEndpointHandler {

  /** CSE 默认端点处理器的 Spring Bean 名称。 */
  public static final String BEAN_NAME = "cseToolEndpointHandler";

  private final McpFabricProperties.Cse properties;
  private final RemoteToolBindingFactory bindingFactory;

  /**
   * 创建 CSE 端点处理器。
   *
   * @param properties MCP 及 CSE 配置
   * @param objectMapper 应用的 Jackson 映射器
   * @param apiFabricClient API Fabric WebClient
   * @param cseClientProvider CSE RestTemplate 提供器
   */
  public CseToolEndpointHandler(
      McpFabricProperties properties,
      ObjectMapper objectMapper,
      WebClient apiFabricClient,
      CseRestTemplateProvider cseClientProvider) {
    this.properties = properties.getCse();
    this.bindingFactory = new RemoteToolBindingFactory(
        objectMapper, apiFabricClient, cseClientProvider, properties.getRequestTimeout());
  }

  /**
   * 获取 CSE 端点类型名称。
   *
   * @return {@code CSE}
   */
  @Override
  public String endpointType() {
    return "CSE";
  }

  /**
   * 获取配置的 CSE 工具引用。
   *
   * @return 不可变且保持配置顺序的引用集合
   */
  @Override
  public Set<String> references() {
    return Collections.unmodifiableSet(new LinkedHashSet<>(properties.getEndpoints().keySet()));
  }

  /**
   * 将匹配的注解方法绑定到 CSE 端点。
   *
   * @param method 注解工具对应的 Java 方法
   * @param registration 扫描得到的工具注册信息
   * @return CSE 远程工具注册信息
   */
  @Override
  public ToolRegistration bind(Method method, ToolRegistration registration) {
    String reference = registration.name();
    McpFabricProperties.CseEndpoint endpoint = properties.getEndpoints().get(reference);
    if (endpoint == null) {
      throw new IllegalArgumentException("CSE endpoint ref=" + reference + ": reference is not configured");
    }
    return bindingFactory.bind(
        endpointType(), method, registration, endpoint, endpoint.getUriTemplate(),
        Tool.Type.CSE);
  }
}
