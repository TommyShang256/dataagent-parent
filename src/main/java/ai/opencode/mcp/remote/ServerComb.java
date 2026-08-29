package ai.opencode.mcp.remote;

import java.util.List;
import java.util.Map;

/** External representation of an internal CSE composition. */
public record ServerComb(String id, List<String> fabricIds, Map<String, String> metadata) {

  public ServerComb {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("ServerComb id must not be blank");
    fabricIds = fabricIds == null ? List.of() : List.copyOf(fabricIds);
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }
}
