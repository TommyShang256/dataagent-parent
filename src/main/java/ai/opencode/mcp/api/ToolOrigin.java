package ai.opencode.mcp.api;

/**
 * 标识标准化工具的来源，供路由和运维使用。
 *
 * @param kind 工具来源类别
 * @param sourceId 工具来源标识
 */
public record ToolOrigin(Kind kind, String sourceId) {

  /**
   * 创建并校验工具来源。
   *
   * @param kind 工具来源类别，不能为 {@code null}
   * @param sourceId 工具来源标识，不能为空
   */
  public ToolOrigin {
    if (kind == null) {
      throw new IllegalArgumentException("Tool origin kind must not be null");
    }
    if (sourceId == null || sourceId.isBlank()) {
      throw new IllegalArgumentException("Tool sourceId must not be blank");
    }
  }

  /**
   * 创建本地工具来源。
   *
   * @param sourceId 本地工具来源标识
   * @return 本地工具来源
   */
  public static ToolOrigin local(String sourceId) {
    return new ToolOrigin(Kind.LOCAL, sourceId);
  }

  /**
   * 创建 API Fabric 工具来源。
   *
   * @param sourceId API Fabric 端点引用
   * @return API Fabric 工具来源
   */
  public static ToolOrigin apiFabric(String sourceId) {
    return new ToolOrigin(Kind.API_FABRIC, sourceId);
  }

  /**
   * 创建 CSE 工具来源。
   *
   * @param sourceId CSE 端点引用
   * @return CSE 工具来源
   */
  public static ToolOrigin serverComb(String sourceId) {
    return new ToolOrigin(Kind.SERVER_COMB, sourceId);
  }

  /** 工具来源类别。 */
  public enum Kind {
    /** 本地注解工具。 */
    LOCAL,

    /** API Fabric 远程工具。 */
    API_FABRIC,

    /** CSE 远程工具。 */
    SERVER_COMB,

    /** 应用编程式注册的自定义工具。 */
    CUSTOM
  }
}
