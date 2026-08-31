package ai.opencode.mcp.remote;

import java.util.Locale;
import java.util.Set;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 传输上下文提取与下游请求共用的 Header 安全规则。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RemoteHeaderPolicy {

  private static final Set<String> EXCLUDED = Set.of(
      "host", "content-length", "connection", "transfer-encoding", "upgrade", "keep-alive", "te", "trailer",
      "accept", "content-type", "mcp-session-id", "last-event-id");

  public static boolean isExcluded(String name) {
    return name == null || EXCLUDED.contains(name.toLowerCase(Locale.ROOT));
  }

  public static void validateValue(String name, String value) {
    if (value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)) {
      throw new IllegalArgumentException("Header contains CR/LF: " + name);
    }
  }
}
