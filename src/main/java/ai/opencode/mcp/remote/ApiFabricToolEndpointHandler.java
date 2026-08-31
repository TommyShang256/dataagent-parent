package ai.opencode.mcp.remote;

import ai.opencode.mcp.annotation.Tool;
import ai.opencode.mcp.api.ToolRegistration;
import ai.opencode.mcp.autoconfigure.McpFabricProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.util.StringUtils;

/**
 * 使用共享基础 URL 解析并绑定 API Fabric 远程工具端点。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
public final class ApiFabricToolEndpointHandler implements RemoteToolEndpointHandler {

  /** API Fabric 默认端点处理器的 Spring Bean 名称。 */
  public static final String BEAN_NAME = "apiFabricToolEndpointHandler";

  private final McpFabricProperties.ApiFabric properties;
  private final RemoteToolBindingFactory bindingFactory;

  /**
   * 创建 API Fabric 端点处理器。
   *
   * @param properties MCP 及 API Fabric 配置
   * @param objectMapper 应用的 Jackson 映射器
   * @param clients 远程 WebClient 提供器
   */
  public ApiFabricToolEndpointHandler(
      McpFabricProperties properties,
      ObjectMapper objectMapper,
      RemoteToolWebClientProvider clients) {
    this.properties = properties.getApiFabric();
    this.bindingFactory = new RemoteToolBindingFactory(
        objectMapper, clients, properties.getRequestTimeout());
  }

  /**
   * 获取 API Fabric 端点类型名称。
   *
   * @return {@code API Fabric}
   */
  @Override
  public String endpointType() {
    return "API Fabric";
  }

  /**
   * 获取配置的 API Fabric 工具引用。
   *
   * @return 不可变且保持配置顺序的引用集合
   */
  @Override
  public Set<String> references() {
    validateBaseUrl();
    return Collections.unmodifiableSet(new LinkedHashSet<>(properties.getEndpoints().keySet()));
  }

  /**
   * 将匹配的注解方法绑定到 API Fabric 端点。
   *
   * @param method 注解工具对应的 Java 方法
   * @param registration 扫描得到的工具注册信息
   * @return API Fabric 远程工具注册信息
   */
  @Override
  public ToolRegistration bind(Method method, ToolRegistration registration) {
    String reference = registration.name();
    McpFabricProperties.ApiFabricEndpoint endpoint = properties.getEndpoints().get(reference);
    if (endpoint == null) {
      throw new IllegalArgumentException("API Fabric 端点 ref=" + reference + ": 未配置该引用");
    }
    String pathTemplate = endpoint.getPathTemplate();
    validatePathTemplate(reference, pathTemplate);
    String template = properties.getBaseUrl().replaceAll("/+$", "") + pathTemplate;
    return bindingFactory.bind(
        endpointType(), method, registration, endpoint, template, Tool.Type.API_FABRIC);
  }

  private static void validatePathTemplate(String reference, String pathTemplate) {
    if (!StringUtils.hasText(pathTemplate)
        || !pathTemplate.startsWith("/")
        || pathTemplate.startsWith("//")) {
      fail(reference, "path-template 必须是以单个 / 开头的路径");
    }
  }

  private void validateBaseUrl() {
    if (properties.getEndpoints().isEmpty()) {
      return;
    }
    String baseUrl = properties.getBaseUrl();
    if (!StringUtils.hasText(baseUrl)) {
      fail("base-url", "不能为空");
    }
    try {
      URI uri = URI.create(baseUrl);
      if (!uri.isAbsolute()) {
        fail("base-url", "必须是绝对 URI");
      }
      if (!Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
        fail("base-url", "必须使用 http 或 https scheme");
      }
    } catch (IllegalArgumentException exception) {
      fail("base-url", "非法 URI");
    }
  }

  private static void fail(String reference, String detail) {
    throw new IllegalStateException("API Fabric 端点 ref=" + reference + ": " + detail);
  }
}
