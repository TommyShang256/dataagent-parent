package ai.opencode.mcp.remote;

import ai.opencode.mcp.api.ToolInvocationContext;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 提取安全的请求 Header，且不保留 Servlet 请求状态。 */
public final class ServletToolContextExtractor implements McpTransportContextExtractor<HttpServletRequest> {

  /** 创建 Servlet 工具调用上下文提取器。 */
  public ServletToolContextExtractor() {}

  /**
   * 从 Servlet 请求提取可安全透传的多值 Header 上下文。
   *
   * @param request 当前 Servlet 请求
   * @return 只包含当前请求 Header 副本的 MCP 传输上下文
   */
  @Override
  public McpTransportContext extract(HttpServletRequest request) {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    Enumeration<String> names = request.getHeaderNames();
    if (names != null) {
      while (names.hasMoreElements()) {
        String name = names.nextElement();
        if (RemoteHeaderPolicy.isExcluded(name)) {
          continue;
        }
        List<String> values = new ArrayList<>();
        Enumeration<String> enumeration = request.getHeaders(name);
        if (enumeration != null) {
          while (enumeration.hasMoreElements()) {
            String value = enumeration.nextElement();
            RemoteHeaderPolicy.validateValue(name, value);
            values.add(value);
          }
        }
        headers.put(name, Collections.unmodifiableList(values));
      }
    }
    return McpTransportContext.create(Map.of(
        ToolInvocationContext.TRANSPORT_HEADERS_KEY, Collections.unmodifiableMap(headers)));
  }
}
