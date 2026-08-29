package ai.opencode.mcp.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.opencode.mcp.api.ToolHints;
import ai.opencode.mcp.api.ToolInvoker;
import ai.opencode.mcp.api.ToolRegistration;
import ai.opencode.mcp.audit.ToolAuditEvent;
import ai.opencode.mcp.audit.ToolAuditLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class McpToolRegistryTest {

  @Test
  void exposesNoRuntimeMutationMethods() {
    assertThat(Arrays.stream(McpToolRegistry.class.getMethods()).map(java.lang.reflect.Method::getName))
        .doesNotContain("register", "remove");

    var registry = registry(List.of(registration("fixed", arguments -> "ok")), new FakeToolServer(), event -> {});
    registry.afterSingletonsInstantiated();

    assertThatThrownBy(registry.tools()::clear).isInstanceOf(UnsupportedOperationException.class);
    assertThat(registry.tools()).extracting(ToolRegistration::name).containsExactly("fixed");
  }

  @Test
  void rejectsDuplicateCatalogBeforePublishingTools() {
    var server = new FakeToolServer();
    var registry = registry(
        List.of(registration("same", arguments -> 1), registration("same", arguments -> 2)), server, event -> {});

    assertThatThrownBy(registry::afterSingletonsInstantiated)
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate", "same");
    assertThat(registry.tools()).isEmpty();
    assertThat(server.added).isEmpty();
  }

  @Test
  void rollsBackServerRegistrationsWhenStartupRegistrationFails() {
    var server = new FakeToolServer();
    server.reject = "second";
    var registry = registry(
        List.of(registration("first", arguments -> 1), registration("second", arguments -> 2)), server, event -> {});

    assertThatThrownBy(registry::afterSingletonsInstantiated)
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("server rejected", "second");
    assertThat(registry.tools()).isEmpty();
    assertThat(server.removed).containsExactly("first");
  }

  @Test
  void auditFailuresDoNotChangeRegistrationOrBusinessOutcome() throws Exception {
    var success = registration("success", arguments -> "ok");
    var failure = registration("failure", arguments -> { throw new IllegalArgumentException("business"); });
    ToolAuditLogger failingAudit = event -> { throw new IllegalStateException("audit unavailable"); };
    var registry = registry(List.of(success, failure), new FakeToolServer(), failingAudit);

    registry.afterSingletonsInstantiated();

    assertThat(registry.tools()).extracting(ToolRegistration::name).containsExactly("success", "failure");
    assertThat(find(registry, "success").invoker().invoke(Map.of())).isEqualTo("ok");
    assertThatThrownBy(() -> find(registry, "failure").invoker().invoke(Map.of()))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("business");
  }

  @Test
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
    assertThat(events).allMatch(event -> event.duration() != null && event.origin() != null);
  }

  @Test
  void mapsMcpFacingResultsAndErrors() {
    var registry = registry(List.of(), new FakeToolServer(), event -> {});
    assertText(registry.call(registration("string", arguments -> "hello"), Map.of()), "hello", false);
    assertText(registry.call(registration("object", arguments -> Map.of("value", 3)), Map.of()),
        "{\"value\":3}", false);
    assertText(registry.call(registration("null", arguments -> null), Map.of()), "null", false);

    var nativeResult = McpSchema.CallToolResult.builder()
        .content(List.of(McpSchema.TextContent.builder("native").build())).isError(false).build();
    assertThat(registry.call(registration("native", arguments -> nativeResult), Map.of())).isSameAs(nativeResult);

    assertText(registry.call(registration("failure", arguments -> { throw new IllegalStateException("failed"); }), Map.of()),
        "failed", true);
    assertText(registry.call(registration("serialization", arguments -> new SelfReference()), Map.of()),
        "Direct self-reference leading to cycle", true);
  }

  @Test
  void generatedSpecificationInvokesMcpHandler() {
    var registry = registry(List.of(), new FakeToolServer(), event -> {});
    var specification = registry.toSpecification(registration("echo", arguments -> arguments.get("message")));
    var result = specification.callHandler().apply(
        null, McpSchema.CallToolRequest.builder("echo")
            .arguments(Map.of("message", "through-handler"))
            .build());

    assertThat(specification.tool().name()).isEqualTo("echo");
    assertText(result, "through-handler", false);
  }

  private static McpToolRegistry registry(
      List<ToolRegistration> registrations, FakeToolServer server, ToolAuditLogger auditLogger) {
    return new McpToolRegistry(
        () -> registrations, new ObjectMapper().findAndRegisterModules(), server, auditLogger);
  }

  private static ToolRegistration find(McpToolRegistry registry, String name) {
    return registry.tools().stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
  }

  private static ToolRegistration registration(String name, ToolInvoker invoker) {
    return new ToolRegistration(
        name, null, null, Map.of("type", "object", "additionalProperties", false), invoker, ToolHints.DEFAULT);
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
      if (name.equals(reject)) throw new IllegalStateException("server rejected " + name);
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
