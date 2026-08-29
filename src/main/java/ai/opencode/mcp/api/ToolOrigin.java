package ai.opencode.mcp.api;

/** Identifies where a normalized tool came from for routing and operations. */
public record ToolOrigin(Kind kind, String sourceId) {

  public ToolOrigin {
    if (kind == null) throw new IllegalArgumentException("Tool origin kind must not be null");
    if (sourceId == null || sourceId.isBlank()) throw new IllegalArgumentException("Tool sourceId must not be blank");
  }

  public static ToolOrigin local(String sourceId) {
    return new ToolOrigin(Kind.LOCAL, sourceId);
  }

  public static ToolOrigin apiFabric(String sourceId) {
    return new ToolOrigin(Kind.API_FABRIC, sourceId);
  }

  public static ToolOrigin serverComb(String sourceId) {
    return new ToolOrigin(Kind.SERVER_COMB, sourceId);
  }

  public enum Kind {
    LOCAL,
    API_FABRIC,
    SERVER_COMB,
    CUSTOM
  }
}
