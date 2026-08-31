package ai.opencode.mcp.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 仅属于当前 tools/call 请求的不可变元数据。 */
public final class ToolInvocationContext {

  /** MCP 传输上下文中保存请求 Header 的键。 */
  public static final String TRANSPORT_HEADERS_KEY = "opencode.mcp.request-headers";

  /** 不包含任何请求元数据的空调用上下文。 */
  public static final ToolInvocationContext EMPTY = new ToolInvocationContext(Map.of());

  private final Map<String, List<String>> headers;

  /**
   * 创建工具调用上下文，并对 Header 名称和值执行防御性复制。
   *
   * @param headers 当前 MCP 请求携带的多值 Header；允许为 {@code null}
   */
  public ToolInvocationContext(Map<String, ? extends List<String>> headers) {
    Map<String, List<String>> copy = new LinkedHashMap<>();
    if (headers != null) {
      headers.forEach((name, values) -> copy.put(name, Collections.unmodifiableList(new ArrayList<>(values))));
    }
    this.headers = Collections.unmodifiableMap(copy);
  }

  /**
   * 获取当前请求可向下游透传的不可变多值 Header。
   *
   * @return 不可变的 Header 映射
   */
  public Map<String, List<String>> headers() {
    return headers;
  }
}
