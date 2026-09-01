package ai.opencode.mcp.api;

import ai.opencode.mcp.annotation.Tool;

import java.util.List;
import java.util.Map;

/**
 * 定义标准化 MCP 工具调用的执行契约。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
@FunctionalInterface
public interface ToolInvoker {

    /**
     * 使用 Agent 提供的参数调用工具。
     *
     * @param arguments 工具参数映射
     * @return 工具执行结果
     * @throws Exception 工具执行失败时抛出
     */
    Object invoke(Map<String, Object> arguments) throws Exception;

    /**
     * 使用 Agent 参数及当前请求可透传 Header 调用工具。
     *
     * @param arguments 工具参数映射
     * @param headers   当前请求的不可变多值 Header
     * @return 工具执行结果
     * @throws Exception 工具执行失败时抛出
     */
    default Object invoke(Map<String, Object> arguments, Map<String, List<String>> headers) throws Exception {
        return invoke(arguments);
    }

    /**
     * 使用业务参数、传输 Header 与 endpoint 绑定调用者调用工具。
     *
     * @param arguments 工具参数映射
     * @param headers 当前请求的不可变多值 Header
     * @param caller endpoint 绑定的调用者
     * @return 工具执行结果
     * @throws Exception 工具执行失败时抛出
     */
    default Object invoke(
            Map<String, Object> arguments,
            Map<String, List<String>> headers,
            Tool.Caller caller) throws Exception {
        return invoke(arguments, headers);
    }
}
