package ai.opencode.mcp.scanner;

import ai.opencode.mcp.api.ToolRegistration;
import java.util.List;

/** Compiles annotation method registrations into final startup registrations. */
@FunctionalInterface
public interface ToolEndpointBinder {

  ToolEndpointBinder LOCAL_ONLY = registrations -> registrations.stream()
      .map(ToolMethodRegistration::registration).toList();

  List<ToolRegistration> bind(List<ToolMethodRegistration> registrations);
}
