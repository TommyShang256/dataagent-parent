package ai.opencode.mcp.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.opencode.mcp.api.ToolOrigin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RemoteToolClientTest {

  private static final RemoteToolClient.ToolDefinition TOOL = new RemoteToolClient.ToolDefinition(
      "echo", null, "Echo", Map.of("type", "object"), null);

  @Test
  void createsImmutableValidatedClient() throws Exception {
    var definitions = new ArrayList<>(List.of(TOOL));
    var client = RemoteToolClient.of(
        "orders", ToolOrigin.Kind.API_FABRIC, definitions, (name, arguments) -> arguments.get("value"));
    definitions.clear();

    assertThat(client.id()).isEqualTo("orders");
    assertThat(client.originKind()).isEqualTo(ToolOrigin.Kind.API_FABRIC);
    assertThat(client.tools()).containsExactly(TOOL);
    assertThat(client.execute("echo", Map.of("value", "ok"))).isEqualTo("ok");
    assertThatThrownBy(() -> client.tools().clear()).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsInvalidFactoryArguments() {
    assertThatThrownBy(() -> RemoteToolClient.of(" ", ToolOrigin.Kind.CUSTOM, List.of(), (n, a) -> null))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("id");
    assertThatThrownBy(() -> RemoteToolClient.of("id", null, List.of(), (n, a) -> null))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("origin");
    assertThatThrownBy(() -> RemoteToolClient.of("id", ToolOrigin.Kind.CUSTOM, null, (n, a) -> null))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tools");
    assertThatThrownBy(() -> RemoteToolClient.of("id", ToolOrigin.Kind.CUSTOM, List.of(), null))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("executor");
  }

  @Test
  void validatesAndCopiesToolDefinitions() {
    var schema = new java.util.LinkedHashMap<String, Object>();
    schema.put("type", "object");
    var definition = new RemoteToolClient.ToolDefinition("echo", null, null, schema, null);
    schema.put("changed", true);

    assertThat(definition.inputSchema()).doesNotContainKey("changed");
    assertThatThrownBy(() -> new RemoteToolClient.ToolDefinition("", null, null, Map.of(), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RemoteToolClient.ToolDefinition("echo", null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
