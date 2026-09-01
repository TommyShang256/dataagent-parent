package ai.opencode.mcp.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.opencode.mcp.annotation.Tool;
import ai.opencode.mcp.api.ToolRegistration;
import ai.opencode.mcp.remote.RemoteToolEndpointHandler;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * 验证 scanner 汇总远程端点处理器并执行目录级校验的行为。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
class McpToolScannerEndpointHandlerTest {

    @Test
    @DisplayName("未匹配远程处理器的工具保持本地执行")
    void leavesUnmatchedToolLocal() throws Exception {
        McpToolScanner scanner = scanner(List.of());

        List<ToolRegistration> result = scanner.scan(new LocalTools());

        assertThat(result).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo("local_tool");
            assertThat(invoke(tool)).isEqualTo("local");
        });
    }

    @Test
    @DisplayName("拒绝多个处理器声明相同远程引用")
    void rejectsDuplicateReferenceAcrossHandlers() {
        McpToolScanner scanner = scanner(List.of(
                new StubEndpointHandler("API Fabric", Set.of("shared")),
                new StubEndpointHandler("CSE", Set.of("shared"))));

        assertThatThrownBy(() -> scanner.scan(new SharedTools()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shared", "API Fabric", "CSE");
    }

    @Test
    @DisplayName("拒绝没有对应注解工具的远程引用")
    void rejectsReferenceWithoutAnnotatedTool() {
        McpToolScanner scanner = scanner(List.of(
                new StubEndpointHandler("Custom endpoint", Set.of("missing"))));

        assertThatThrownBy(() -> scanner.scan(new LocalTools()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Custom endpoint", "missing", "no matching annotated tool");
    }

    @Test
    @DisplayName("拒绝 null、空类型、null 引用集和空白引用的远程处理器")
    void rejectsMalformedEndpointHandlers() {
        assertThat(scanner(null).scan(new LocalTools())).hasSize(1);
        assertThatThrownBy(() -> scanner(Arrays.asList((RemoteToolEndpointHandler) null)).scan(new LocalTools()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not be null");
        assertThatThrownBy(() -> scanner(List.of(new StubEndpointHandler(" ", Set.of())))
                .scan(new LocalTools()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("type must not be blank");
        assertThatThrownBy(() -> scanner(List.of(new StubEndpointHandler("Null references", null)))
                .scan(new LocalTools()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null reference set");
        assertThatThrownBy(() -> scanner(List.of(new StubEndpointHandler("Blank reference", Set.of(" "))))
                .scan(new LocalTools()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ref must not be blank");
    }

    private static McpToolScanner scanner(List<RemoteToolEndpointHandler> handlers) {
        return new McpToolScanner(
                new DefaultListableBeanFactory(), new ObjectMapper().findAndRegisterModules(), handlers);
    }

    private static Object invoke(ToolRegistration registration) {
        try {
            return registration.invoker().invoke(Map.of());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    static class LocalTools {

        @Tool(name = "local_tool")
        String local() {
            return "local";
        }
    }

    static class SharedTools {

        @Tool(name = "shared")
        String shared() {
            return "local";
        }
    }

    private static final class StubEndpointHandler implements RemoteToolEndpointHandler {

        private final String endpointType;
        private final Set<String> references;

        private StubEndpointHandler(String endpointType, Set<String> references) {
            this.endpointType = endpointType;
            this.references = references;
        }

        /**
         * 获取测试端点类型。
         *
         * @return 测试端点类型
         */
        @Override
        public String endpointType() {
            return endpointType;
        }

        /**
         * 获取测试端点引用。
         *
         * @return 测试端点引用
         */
        @Override
        public Set<String> references() {
            return references;
        }

        /**
         * 返回测试输入注册信息。
         *
         * @param method       注解工具对应的 Java 方法
         * @param registration 工具注册信息
         * @return 原始工具注册信息
         */
        @Override
        public ToolRegistration bind(Method method, ToolRegistration registration) {
            return registration;
        }
    }
}
