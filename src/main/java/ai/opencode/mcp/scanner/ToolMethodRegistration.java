package ai.opencode.mcp.scanner;

import ai.opencode.mcp.api.ToolRegistration;
import java.lang.reflect.Method;

/** Annotation method metadata retained until endpoint binding completes. */
public record ToolMethodRegistration(Method method, ToolRegistration registration) {}
