package ai.opencode.mcp.remote;

import ai.opencode.mcp.api.ToolInvocationContext;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;

/** 提取安全的请求 Header，且不保留 Servlet 请求状态。 */
public final class ServletToolContextExtractor implements McpTransportContextExtractor<HttpServletRequest> {

  @Override
  public McpTransportContext extract(HttpServletRequest request) {
    var headers = new LinkedHashMap<String, java.util.List<String>>();
    var names = request.getHeaderNames();
    if (names != null) {
      while (names.hasMoreElements()) {
        var name = names.nextElement();
        if (RemoteHeaderPolicy.isExcluded(name)) continue;
        var values = new ArrayList<String>();
        var enumeration = request.getHeaders(name);
        if (enumeration != null) {
          while (enumeration.hasMoreElements()) {
            var value = enumeration.nextElement();
            RemoteHeaderPolicy.validateValue(name, value);
            values.add(value);
          }
        }
        headers.put(name, Collections.unmodifiableList(values));
      }
    }
    return McpTransportContext.create(java.util.Map.of(
        ToolInvocationContext.TRANSPORT_HEADERS_KEY, Collections.unmodifiableMap(headers)));
  }
}
