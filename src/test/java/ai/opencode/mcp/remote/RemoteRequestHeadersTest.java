package ai.opencode.mcp.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.common.McpTransportContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 验证 Servlet 请求 Header 的提取、隔离和安全过滤行为。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
class RemoteRequestHeadersTest {

    @Test
    @SuppressWarnings("unchecked")
    void capturesAllOrdinaryValuesAndExcludesSystemHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "one");
        request.addHeader("Authorization", "two");
        request.addHeader("X-Trace-Id", "trace");
        request.addHeader("Host", "localhost");
        request.addHeader("Content-Type", "application/json");
        request.addHeader("Mcp-Session-Id", "session");

        McpTransportContext context = new RemoteRequestHeaders().extract(request);
        Map<String, List<String>> headers =
                (Map<String, List<String>>) context.get(RemoteRequestHeaders.class.getName());

        assertThat(headers).containsEntry("Authorization", List.of("one", "two"))
                .containsEntry("X-Trace-Id", List.of("trace"));
        assertThat(headers.keySet()).noneMatch(RemoteRequestHeaders::isExcluded);
        assertThatThrownBy(() -> headers.put("new", List.of("value")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsHeaderInjection() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace", "safe\r\nInjected: value");
        assertThatThrownBy(() -> new RemoteRequestHeaders().extract(request))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CR/LF", "X-Trace");
    }

    @Test
    void returnsEmptyHeadersForMissingOrInvalidTransportValues() {
        assertThat(RemoteRequestHeaders.from(null)).isEmpty();
        assertThat(RemoteRequestHeaders.from(McpTransportContext.create(Map.of()))).isEmpty();
        assertThat(RemoteRequestHeaders.from(McpTransportContext.create(Map.of(
                RemoteRequestHeaders.class.getName(), "invalid")))).isEmpty();
    }

    @Test
    void filtersInvalidEntriesAndReturnsDefensiveImmutableCopy() {
        List<Object> sourceValues = new ArrayList<>(List.of("one", 2));
        Map<Object, Object> source = new LinkedHashMap<>();
        source.put("X-Test", sourceValues);
        source.put("X-Invalid-Value", "invalid");
        source.put(3, List.of("invalid"));
        McpTransportContext context = McpTransportContext.create(Map.of(
                RemoteRequestHeaders.class.getName(), source));

        Map<String, List<String>> headers = RemoteRequestHeaders.from(context);
        sourceValues.add("late");
        source.put("X-Late", List.of("late"));

        assertThat(headers).containsOnly(Map.entry("X-Test", List.of("one")));
        assertThatThrownBy(() -> headers.put("X-New", List.of("new")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> headers.get("X-Test").add("new"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
