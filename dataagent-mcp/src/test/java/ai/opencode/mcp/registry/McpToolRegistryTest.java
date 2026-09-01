package ai.opencode.mcp.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.opencode.mcp.annotation.Tool;
import ai.opencode.mcp.api.ToolInvoker;
import ai.opencode.mcp.api.ToolRegistration;
import ai.opencode.mcp.audit.ToolAuditEvent;
import ai.opencode.mcp.audit.ToolAuditLogger;
import ai.opencode.mcp.remote.RemoteRequestHeaders;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 验证 MCP 工具目录发布、回滚、调用和审计行为。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
class McpToolRegistryTest {

    @Test
    @DisplayName("注册表不暴露运行时修改方法")
    void exposesNoRuntimeMutationMethods() {
        assertThat(Arrays.stream(McpToolRegistry.class.getMethods()).map(java.lang.reflect.Method::getName))
                .doesNotContain("register", "remove");

        var registry = registry(List.of(registration("fixed", arguments -> "ok")), new FakeToolServer(), event -> {
        });
        registry.afterSingletonsInstantiated();

        assertThatThrownBy(registry.tools()::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(registry.tools()).extracting(ToolRegistration::name).containsExactly("fixed");
    }

    @Test
    @DisplayName("发布工具前拒绝重复目录项")
    void rejectsDuplicateCatalogBeforePublishingTools() {
        var server = new FakeToolServer();
        var registry = registry(
                List.of(registration("same", arguments -> 1), registration("same", arguments -> 2)), server, event -> {
                });

        assertThatThrownBy(registry::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate", "same");
        assertThat(registry.tools()).isEmpty();
        assertThat(server.added).isEmpty();
    }

    @Test
    @DisplayName("启动注册失败时回滚服务端工具")
    void rollsBackServerRegistrationsWhenStartupRegistrationFails() {
        var server = new FakeToolServer();
        server.reject = "second";
        var registry = registry(
                List.of(registration("first", arguments -> 1), registration("second", arguments -> 2)), server, event -> {
                });

        assertThatThrownBy(registry::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("server rejected", "second");
        assertThat(registry.tools()).isEmpty();
        assertThat(server.removed).containsExactly("first");
    }

    @Test
    @DisplayName("按调用者把工具发布到隔离的服务端目录")
    void publishesToolsToCallerSpecificCatalogs() {
        FakeToolServer agentServer = new FakeToolServer();
        FakeToolServer scriptServer = new FakeToolServer();
        List<ToolRegistration> registrations = List.of(
                registration("agent", arguments -> "agent", Set.of(Tool.Caller.AGENT)),
                registration("script", arguments -> "script", Set.of(Tool.Caller.SCRIPT)),
                registration("shared", arguments -> "shared", Set.of(Tool.Caller.AGENT, Tool.Caller.SCRIPT)));
        McpToolRegistry registry = new McpToolRegistry(
                () -> registrations,
                new ObjectMapper().findAndRegisterModules(),
                Map.of(Tool.Caller.AGENT, agentServer, Tool.Caller.SCRIPT, scriptServer),
                event -> {
                });

        registry.afterSingletonsInstantiated();

        assertThat(agentServer.added).containsExactly("agent", "shared");
        assertThat(scriptServer.added).containsExactly("script", "shared");
    }

    @Test
    @DisplayName("审计失败不改变注册与业务结果")
    void auditFailuresDoNotChangeRegistrationOrBusinessOutcome() throws Exception {
        var success = registration("success", arguments -> "ok");
        var failure = registration("failure", arguments -> {
            throw new IllegalArgumentException("business");
        });
        ToolAuditLogger failingAudit = event -> {
            throw new IllegalStateException("audit unavailable");
        };
        var registry = registry(List.of(success, failure), new FakeToolServer(), failingAudit);

        registry.afterSingletonsInstantiated();

        assertThat(registry.tools()).extracting(ToolRegistration::name).containsExactly("success", "failure");
        assertThat(find(registry, "success").invoker().invoke(Map.of())).isEqualTo("ok");
        assertThatThrownBy(() -> find(registry, "failure").invoker().invoke(Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("business");
    }

    @Test
    @DisplayName("记录完整的注册与调用审计事件")
    void recordsCompleteRegistrationAndInvocationEvents() throws Exception {
        var events = new ArrayList<ToolAuditEvent>();
        var registry = registry(List.of(registration("echo", arguments -> arguments.get("message"))),
                new FakeToolServer(), events::add);

        registry.afterSingletonsInstantiated();
        assertThat(find(registry, "echo").invoker().invoke(Map.of("message", "hello"))).isEqualTo("hello");

        assertThat(events).extracting(ToolAuditEvent::operation)
                .containsExactly(ToolAuditEvent.Operation.REGISTER, ToolAuditEvent.Operation.INVOKE);
        assertThat(events.get(1).arguments()).containsEntry("message", "hello");
        assertThat(events.get(1).result()).isEqualTo("hello");
        assertThat(events).allMatch(event -> event.duration() != null && event.type() != null);
    }

    @Test
    @DisplayName("映射 MCP 调用结果与错误")
    void mapsMcpFacingResultsAndErrors() {
        var registry = registry(List.of(), new FakeToolServer(), event -> {
        });
        assertText(registry.call(registration("string", arguments -> "hello"), Map.of()), "hello", false);
        assertText(registry.call(registration("object", arguments -> Map.of("value", 3)), Map.of()),
                "{\"value\":3}", false);
        assertText(registry.call(registration("null", arguments -> null), Map.of()), "null", false);
        assertText(registry.call(
                registration("null-arguments", arguments -> arguments.isEmpty() ? "empty" : "unexpected"),
                null, Map.of()), "empty", false);

        var nativeResult = McpSchema.CallToolResult.builder()
                .content(List.of(McpSchema.TextContent.builder("native").build())).isError(false).build();
        assertThat(registry.call(registration("native", arguments -> nativeResult), Map.of())).isSameAs(nativeResult);

        assertText(registry.call(registration("failure", arguments -> {
                    throw new IllegalStateException("failed");
                }), Map.of()),
                "failed", true);
        assertText(registry.call(registration("serialization", arguments -> new SelfReference()), Map.of()),
                "Direct self-reference leading to cycle", true);
    }

    @Test
    @DisplayName("生成的工具规格调用 MCP 处理器")
    void generatedSpecificationInvokesMcpHandler() {
        McpToolRegistry registry = registry(List.of(), new FakeToolServer(), event -> {
        });
        ToolRegistration registration = new ToolRegistration(
                "echo",
                new ToolRegistration.Definition(
                        "回显", null, Map.of("type", "object", "additionalProperties", false)),
                arguments -> arguments.get("message"),
                Tool.Type.LOCAL,
                new ToolRegistration.Behavior(
                        true, false, true, false, Set.of(Tool.Caller.AGENT)));
        McpServerFeatures.SyncToolSpecification specification = registry.toSpecification(registration);
        McpSchema.CallToolResult result = specification.callHandler().apply(
                null, McpSchema.CallToolRequest.builder("echo")
                        .arguments(Map.of("message", "through-handler"))
                        .build());

        assertThat(specification.tool().name()).isEqualTo("echo");
        assertThat(specification.tool().annotations().title()).isEqualTo("回显");
        assertThat(specification.tool().annotations().readOnlyHint()).isTrue();
        assertThat(specification.tool().annotations().destructiveHint()).isFalse();
        assertThat(specification.tool().annotations().idempotentHint()).isTrue();
        assertThat(specification.tool().annotations().openWorldHint()).isFalse();
        assertThat(specification.tool().meta()).containsEntry(
                McpToolRegistry.ALLOWED_CALLERS_META_KEY, List.of("agent"));
        assertText(result, "through-handler", false);
    }

    @Test
    @DisplayName("传递传输上下文且不审计请求 Header")
    void passesTransportContextThroughHandlerAndAuditWrapperWithoutAuditingHeaders() throws Exception {
        var received = new AtomicReference<Map<String, List<String>>>();
        ToolInvoker contextAware = new ToolInvoker() {
            @Override
            public Object invoke(Map<String, Object> arguments) {
                return "without-context";
            }

            @Override
            public Object invoke(Map<String, Object> arguments, Map<String, List<String>> headers) {
                received.set(headers);
                return arguments.get("message");
            }
        };
        var events = new ArrayList<ToolAuditEvent>();
        var registry = registry(List.of(registration("context", contextAware)), new FakeToolServer(), events::add);
        registry.afterSingletonsInstantiated();

        Map<String, List<String>> first = Map.of("Authorization", List.of("first"));
        assertThat(find(registry, "context").invoker().invoke(Map.of("message", "one"), first)).isEqualTo("one");
        assertThat(received.get()).containsEntry("Authorization", List.of("first"));
        assertThat(events.getLast().arguments()).containsOnlyKeys("message");

        var transport = io.modelcontextprotocol.common.McpTransportContext.create(Map.of(
                RemoteRequestHeaders.class.getName(),
                Map.of("Authorization", List.of("second"))));
        var session = new io.modelcontextprotocol.spec.McpLoggableSession() {
            @Override
            public <T> reactor.core.publisher.Mono<T> sendRequest(
                    String method, Object parameters, io.modelcontextprotocol.json.TypeRef<T> typeRef) {
                return reactor.core.publisher.Mono.empty();
            }

            @Override
            public reactor.core.publisher.Mono<Void> sendNotification(String method, Object parameters) {
                return reactor.core.publisher.Mono.empty();
            }

            @Override
            public reactor.core.publisher.Mono<Void> closeGracefully() {
                return reactor.core.publisher.Mono.empty();
            }

            @Override
            public void close() {
            }

            @Override
            public void setMinLoggingLevel(McpSchema.LoggingLevel level) {
            }

            @Override
            public boolean isNotificationForLevelAllowed(McpSchema.LoggingLevel level) {
                return true;
            }
        };
        var asyncExchange = new io.modelcontextprotocol.server.McpAsyncServerExchange(
                "session", session, null, null, transport);
        var exchange = new io.modelcontextprotocol.server.McpSyncServerExchange(asyncExchange);
        var result = registry.toSpecification(find(registry, "context")).callHandler().apply(
                exchange, McpSchema.CallToolRequest.builder("context").arguments(Map.of("message", "two")).build());
        assertText(result, "two", false);
        assertThat(received.get()).containsEntry("Authorization", List.of("second"));
    }

    @Test
    @DisplayName("按工具调用者策略拒绝 Agent 与 Script 越权调用")
    void enforcesCallerPolicy() {
        ToolRegistration agentOnly = registration(
                "agent", arguments -> "agent", Set.of(Tool.Caller.AGENT));
        ToolRegistration scriptOnly = registration(
                "script", arguments -> "script", Set.of(Tool.Caller.SCRIPT));
        McpToolRegistry registry = registry(List.of(), new FakeToolServer(), event -> {
        });
        assertText(registry.call(agentOnly, Map.of(), Map.of(), Tool.Caller.SCRIPT), "not allowed", true);
        assertText(registry.call(scriptOnly, Map.of(), Map.of(), Tool.Caller.SCRIPT), "script", false);
        assertText(registry.call(scriptOnly, Map.of()), "not allowed", true);
    }

    @Test
    @DisplayName("endpoint 调用者不进入业务参数并写入审计")
    void keepsCallerOutsideArgumentsAndAuditsCaller() {
        List<ToolAuditEvent> events = new ArrayList<>();
        ToolRegistration shared = registration(
                "shared", arguments -> arguments, Set.of(Tool.Caller.AGENT, Tool.Caller.SCRIPT));
        McpToolRegistry registry = registry(List.of(shared), new FakeToolServer(), events::add);
        registry.afterSingletonsInstantiated();
        McpSchema.CallToolResult result = registry.call(
                find(registry, "shared"), Map.of("value", 1), Map.of(), Tool.Caller.SCRIPT);

        assertText(result, "value", false);
        ToolAuditEvent invocation = events.getLast();
        assertThat(invocation.arguments()).containsOnlyKeys("value");
        assertThat(invocation.caller()).isEqualTo(Tool.Caller.SCRIPT);
    }

    private static McpToolRegistry registry(
            List<ToolRegistration> registrations, FakeToolServer server, ToolAuditLogger auditLogger) {
        return new McpToolRegistry(
                () -> registrations,
                new ObjectMapper().findAndRegisterModules(),
                Map.of(Tool.Caller.AGENT, server, Tool.Caller.SCRIPT, server),
                auditLogger);
    }

    private static ToolRegistration find(McpToolRegistry registry, String name) {
        return registry.tools().stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    private static ToolRegistration registration(String name, ToolInvoker invoker) {
        return registration(name, invoker, Set.of(Tool.Caller.AGENT));
    }

    private static ToolRegistration registration(
            String name, ToolInvoker invoker, Set<Tool.Caller> allowedCallers) {
        return new ToolRegistration(
                name,
                new ToolRegistration.Definition(
                        null, null, Map.of("type", "object", "additionalProperties", false)),
                invoker,
                Tool.Type.LOCAL,
                new ToolRegistration.Behavior(false, true, false, true, allowedCallers));
    }

    private static void assertText(McpSchema.CallToolResult result, String text, boolean error) {
        assertThat(result.isError()).isEqualTo(error);
        assertThat(result.content()).singleElement().isInstanceOf(McpSchema.TextContent.class)
                .satisfies(content -> assertThat(((McpSchema.TextContent) content).text()).contains(text));
    }

    static class FakeToolServer implements McpToolRegistry.ToolServer {
        private final List<String> added = new ArrayList<>();
        private final List<String> removed = new ArrayList<>();
        private String reject;

        @Override
        public void add(McpServerFeatures.SyncToolSpecification specification) {
            var name = specification.tool().name();
            if (name.equals(reject)) {
                throw new IllegalStateException("server rejected " + name);
            }
            added.add(name);
        }

        @Override
        public void remove(String name) {
            removed.add(name);
        }
    }

    static class SelfReference {
        public SelfReference self = this;
    }
}
