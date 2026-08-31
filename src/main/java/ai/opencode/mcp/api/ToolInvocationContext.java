package ai.opencode.mcp.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.experimental.Accessors;

/** 仅属于当前 tools/call 请求的不可变元数据。 */
public final class ToolInvocationContext {

  public static final String TRANSPORT_HEADERS_KEY = "opencode.mcp.request-headers";
  public static final ToolInvocationContext EMPTY = new ToolInvocationContext(Map.of());

  @Getter
  @Accessors(fluent = true)
  private final Map<String, List<String>> headers;

  public ToolInvocationContext(Map<String, ? extends List<String>> headers) {
    var copy = new LinkedHashMap<String, List<String>>();
    if (headers != null) {
      headers.forEach((name, values) -> copy.put(name, Collections.unmodifiableList(new ArrayList<>(values))));
    }
    this.headers = Collections.unmodifiableMap(copy);
  }
}
