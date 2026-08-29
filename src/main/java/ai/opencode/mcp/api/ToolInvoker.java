package ai.opencode.mcp.api;

import java.util.Map;

@FunctionalInterface
public interface ToolInvoker {

  Object invoke(Map<String, Object> arguments) throws Exception;
}
