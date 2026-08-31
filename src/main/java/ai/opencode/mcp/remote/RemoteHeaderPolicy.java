package ai.opencode.mcp.remote;

import java.util.Locale;
import java.util.Set;

/**
 * 提供传输上下文提取与下游请求共用的 Header 安全规则。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
public final class RemoteHeaderPolicy {

  private static final Set<String> EXCLUDED = Set.of(
      "host", "content-length", "connection", "transfer-encoding", "upgrade", "keep-alive", "te", "trailer",
      "accept", "content-type", "mcp-session-id", "last-event-id");

  /** 禁止实例化静态策略类。 */
  private RemoteHeaderPolicy() {}

  /**
   * 判断 Header 是否属于禁止透传的系统名称。
   *
   * @param name Header 名称
   * @return 名称为空或属于系统排除集合时返回 {@code true}
   */
  public static boolean isExcluded(String name) {
    return name == null || EXCLUDED.contains(name.toLowerCase(Locale.ROOT));
  }

  /**
   * 校验 Header 值不包含 CR 或 LF，防止 Header 注入。
   *
   * @param name Header 名称，用于错误诊断
   * @param value Header 值；允许为 {@code null}
   * 返回值：无。
   */
  public static void validateValue(String name, String value) {
    if (value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)) {
      throw new IllegalArgumentException("Header contains CR/LF: " + name);
    }
  }
}
