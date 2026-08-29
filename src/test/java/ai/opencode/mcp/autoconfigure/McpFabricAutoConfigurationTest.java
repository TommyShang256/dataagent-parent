package ai.opencode.mcp.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.opencode.mcp.annotation.Tool;
import ai.opencode.mcp.annotation.ToolParam;
import ai.opencode.mcp.api.ToolHints;
import ai.opencode.mcp.api.ToolOrigin;
import ai.opencode.mcp.api.ToolRegistration;
import ai.opencode.mcp.audit.Slf4jToolAuditLogger;
import ai.opencode.mcp.audit.ToolAuditEvent;
import ai.opencode.mcp.audit.ToolAuditLogger;
import ai.opencode.mcp.registry.McpToolRegistry;
import ai.opencode.mcp.remote.ApiFabricClient;
import ai.opencode.mcp.remote.CseClient;
import ai.opencode.mcp.remote.RemoteToolClient;
import ai.opencode.mcp.remote.RemoteToolDefinition;
import ai.opencode.mcp.scanner.McpToolScanner;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class McpFabricAutoConfigurationTest {

  private final WebApplicationContextRunner baseRunner = new WebApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, McpFabricAutoConfiguration.class));

  private final WebApplicationContextRunner runner = baseRunner.withUserConfiguration(TestConfiguration.class);

  @Test
  void registersLocalAndRemoteTools() {
    runner.run(context -> {
      assertThat(context).hasSingleBean(McpToolScanner.class);
      assertThat(context).hasSingleBean(McpToolRegistry.class);
      assertThat(context.getBeansOfType(RemoteToolClient.class)).hasSize(2);
      var servletRegistration = (ServletRegistrationBean<?>) context.getBean("mcpServletRegistration");
      assertThat(servletRegistration.getUrlMappings()).containsExactly("/mcp");
      var tools = context.getBean(McpToolRegistry.class).tools();
      assertThat(tools).extracting(ToolRegistration::name)
          .containsExactlyInAnyOrder("local_echo", "fabric_echo", "server_comb_echo");
      assertThat(tools).filteredOn(tool -> tool.name().equals("local_echo"))
          .extracting(ToolRegistration::origin)
          .containsExactly(ToolOrigin.local(LocalTools.class.getName()));
      assertThat(tools).filteredOn(tool -> tool.name().equals("fabric_echo"))
          .extracting(tool -> tool.origin().kind())
          .containsExactly(ToolOrigin.Kind.API_FABRIC);
      assertThat(tools).filteredOn(tool -> tool.name().equals("server_comb_echo"))
          .extracting(ToolRegistration::origin)
          .containsExactly(ToolOrigin.serverComb("inventory"));
    });
  }

  @Test
  void invokesLocalAndRemoteToolsAndAuditsOperations() {
    runner.run(context -> {
      var registry = context.getBean(McpToolRegistry.class);
      assertThat(invoke(registry, "local_echo", Map.of("message", "hello"))).isEqualTo("hello");
      assertThat(invoke(registry, "fabric_echo", Map.of())).isEqualTo("api-fabric");
      assertThat(invoke(registry, "server_comb_echo", Map.of())).isEqualTo("cse");

      var events = context.getBean(RecordingAuditLogger.class).events;
      assertThat(events).filteredOn(event -> event.operation() == ToolAuditEvent.Operation.REGISTER)
          .hasSize(3)
          .allMatch(event -> event.outcome() == ToolAuditEvent.Outcome.SUCCESS);
      assertThat(events).filteredOn(event -> event.operation() == ToolAuditEvent.Operation.INVOKE)
          .hasSize(3)
          .allMatch(event -> event.outcome() == ToolAuditEvent.Outcome.SUCCESS)
          .allMatch(event -> event.duration() != null);
      assertThat(events).filteredOn(event -> event.toolName().equals("local_echo"))
          .filteredOn(event -> event.operation() == ToolAuditEvent.Operation.INVOKE)
          .singleElement()
          .satisfies(event -> {
            assertThat(event.arguments()).containsEntry("message", "hello");
            assertThat(event.result()).isEqualTo("hello");
          });
    });
  }

  @Test
  void usesSlf4jAuditLoggerByDefault() {
    baseRunner.run(context -> assertThat(context.getBean(ToolAuditLogger.class))
        .isInstanceOf(Slf4jToolAuditLogger.class));
  }

  @Test
  void auditsFailedInvocationArguments() {
    runner.run(context -> {
      var registry = context.getBean(McpToolRegistry.class);
      registry.register(new ToolRegistration(
          "failing_tool",
          null,
          null,
          Map.of("type", "object"),
          arguments -> {
            throw new IllegalStateException("sensitive failure detail");
          },
          ToolHints.DEFAULT));

      assertThatThrownBy(() -> invoke(registry, "failing_tool", Map.of("secret", "value")))
          .isInstanceOf(IllegalStateException.class);
      assertThat(context.getBean(RecordingAuditLogger.class).events)
          .filteredOn(event -> event.toolName().equals("failing_tool"))
          .filteredOn(event -> event.operation() == ToolAuditEvent.Operation.INVOKE)
          .singleElement()
          .satisfies(event -> {
            assertThat(event.outcome()).isEqualTo(ToolAuditEvent.Outcome.FAILURE);
            assertThat(event.errorType()).isEqualTo(IllegalStateException.class.getName());
            assertThat(event.arguments()).containsEntry("secret", "value");
            assertThat(event.result()).isNull();
          });
    });
  }

  private static Object invoke(McpToolRegistry registry, String name, Map<String, Object> arguments) throws Exception {
    return registry.tools().stream()
        .filter(tool -> tool.name().equals(name))
        .findFirst()
        .orElseThrow()
        .invoker()
        .invoke(arguments);
  }

  @Configuration(proxyBeanMethods = false)
  static class TestConfiguration {

    @Bean
    LocalTools localTools() {
      return new LocalTools();
    }

    @Bean
    ApiFabricClient apiFabricClient() {
      return new ApiFabricClient() {
        @Override
        public String id() {
          return "orders";
        }

        @Override
        public Collection<RemoteToolDefinition> tools() {
          return List.of(remoteTool("fabric_echo", "Remote echo"));
        }

        @Override
        public Object execute(String toolName, Map<String, Object> arguments) {
          return "api-fabric";
        }
      };
    }

    @Bean
    CseClient cseClient() {
      return new CseClient() {
        @Override
        public String id() {
          return "inventory";
        }

        @Override
        public Collection<RemoteToolDefinition> tools() {
          return List.of(remoteTool("server_comb_echo", "ServerComb echo"));
        }

        @Override
        public Object execute(String toolName, Map<String, Object> arguments) {
          return "cse";
        }
      };
    }

    @Bean
    RecordingAuditLogger toolAuditLogger() {
      return new RecordingAuditLogger();
    }

    private static RemoteToolDefinition remoteTool(String name, String description) {
      return new RemoteToolDefinition(
          name,
          null,
          description,
          Map.of("type", "object", "additionalProperties", false),
          ToolHints.DEFAULT);
    }
  }

  static class RecordingAuditLogger implements ToolAuditLogger {

    private final List<ToolAuditEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void record(ToolAuditEvent event) {
      events.add(event);
    }
  }

  static class LocalTools {

    @Tool(name = "local_echo", description = "Echo a value", readOnly = true)
    String echo(@ToolParam(description = "Value to echo") String message) {
      return message;
    }
  }
}
