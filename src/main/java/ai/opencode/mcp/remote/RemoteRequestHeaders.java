package ai.opencode.mcp.remote;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 提取当前 Servlet 请求 Header，并统一执行远程透传安全规则。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
public final class RemoteRequestHeaders implements McpTransportContextExtractor<HttpServletRequest> {

    private static final Set<String> EXCLUDED = Set.of(
            "host", "content-length", "connection", "transfer-encoding", "upgrade", "keep-alive", "te", "trailer",
            "accept", "content-type", "mcp-session-id", "last-event-id");

    /**
     * 提取可安全透传的多值 Header，并与当前请求隔离。
     *
     * @param request 当前 Servlet 请求
     * @return 只包含当前请求 Header 不可变副本的 MCP 传输上下文
     */
    @Override
    public McpTransportContext extract(HttpServletRequest request) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                if (isExcluded(name)) {
                    continue;
                }
                List<String> values = new ArrayList<>();
                Enumeration<String> enumeration = request.getHeaders(name);
                if (enumeration != null) {
                    while (enumeration.hasMoreElements()) {
                        String value = enumeration.nextElement();
                        validateValue(name, value);
                        values.add(value);
                    }
                }
                headers.put(name, List.copyOf(values));
            }
        }
        return McpTransportContext.create(Map.of(
                RemoteRequestHeaders.class.getName(), Collections.unmodifiableMap(headers)));
    }

    static boolean isExcluded(String name) {
        return name == null || EXCLUDED.contains(name.toLowerCase(Locale.ROOT));
    }

    static void validateValue(String name, String value) {
        if (value != null && (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)) {
            throw new IllegalArgumentException("Header contains CR/LF: " + name);
        }
    }
}
