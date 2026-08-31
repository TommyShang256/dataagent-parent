package ai.opencode.mcp.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 验证 Servlet 请求 Header 上下文的提取与过滤行为。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
class ServletToolContextExtractorTest {

  @Test
  @SuppressWarnings("unchecked")
  void capturesAllOrdinaryValuesAndExcludesSystemHeaders() {
    var request = new MockHttpServletRequest();
    request.addHeader("Authorization", "one");
    request.addHeader("Authorization", "two");
    request.addHeader("X-Trace-Id", "trace");
    request.addHeader("Host", "localhost");
    request.addHeader("Content-Type", "application/json");
    request.addHeader("Mcp-Session-Id", "session");

    var context = new ServletToolContextExtractor().extract(request);
    var headers = (Map<String, List<String>>) context.get(ServletToolContextExtractor.class.getName());

    assertThat(headers).containsEntry("Authorization", List.of("one", "two"))
        .containsEntry("X-Trace-Id", List.of("trace"));
    assertThat(headers.keySet()).noneMatch(RemoteHeaderPolicy::isExcluded);
    assertThatThrownBy(() -> headers.put("new", List.of("value")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsHeaderInjection() {
    var request = new MockHttpServletRequest();
    request.addHeader("X-Trace", "safe\r\nInjected: value");
    assertThatThrownBy(() -> new ServletToolContextExtractor().extract(request))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CR/LF", "X-Trace");
  }
}
