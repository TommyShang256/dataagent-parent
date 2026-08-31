package ai.opencode.mcp.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.opencode.mcp.annotation.Tool;
import ai.opencode.mcp.annotation.ToolParam;
import ai.opencode.mcp.api.ToolRegistration;
import ai.opencode.mcp.audit.Slf4jToolAuditLogger;
import ai.opencode.mcp.audit.ToolAuditEvent;
import ai.opencode.mcp.audit.ToolAuditLogger;
import ai.opencode.mcp.registry.McpToolRegistry;
import ai.opencode.mcp.remote.ApiFabricToolEndpointHandler;
import ai.opencode.mcp.remote.CseToolEndpointHandler;
import ai.opencode.mcp.remote.CseRestTemplateProvider;
import ai.opencode.mcp.remote.RemoteToolEndpointHandler;
import ai.opencode.mcp.scanner.McpToolScanner;
import io.modelcontextprotocol.server.McpSyncServer;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.client.RestTemplate;

/**
 * 验证 MCP 自动配置、条件装配和远程端点处理器替换行为。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
class McpFabricAutoConfigurationTest {

  private final WebApplicationContextRunner baseRunner = new WebApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, McpFabricAutoConfiguration.class));

  private final WebApplicationContextRunner runner = baseRunner.withUserConfiguration(TestConfiguration.class);

  @Test
  void registersAnnotatedTools() {
    runner.run(context -> {
      assertThat(context).hasSingleBean(McpToolScanner.class);
      assertThat(context).hasSingleBean(McpToolRegistry.class);
      var servletRegistration = (ServletRegistrationBean<?>) context.getBean("mcpServletRegistration");
      assertThat(servletRegistration.getUrlMappings()).containsExactly("/rest/mcp");
      var tools = context.getBean(McpToolRegistry.class).tools();
      assertThat(tools).extracting(ToolRegistration::name)
          .containsExactlyInAnyOrder("local_echo", "second_echo", "third_echo", "failing_tool");
      assertThat(tools).extracting(ToolRegistration::type)
          .containsOnly(Tool.Type.LOCAL);
    });
  }

  @Test
  void invokesAnnotatedToolsAndAuditsOperations() {
    runner.run(context -> {
      var registry = context.getBean(McpToolRegistry.class);
      assertThat(invoke(registry, "local_echo", Map.of("message", "hello"))).isEqualTo("hello");
      assertThat(invoke(registry, "second_echo", Map.of())).isEqualTo("second");
      assertThat(invoke(registry, "third_echo", Map.of())).isEqualTo("third");

      var events = context.getBean(RecordingAuditLogger.class).events;
      assertThat(events).filteredOn(event -> event.operation() == ToolAuditEvent.Operation.REGISTER)
          .hasSize(4)
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

  @Test
  void advertisesOnlyToolsAndPublishesStartupCatalog() {
    runner.run(context -> {
      var server = context.getBean(McpSyncServer.class);
      var capabilities = server.getServerCapabilities();

      assertThat(capabilities.tools()).isNotNull();
      assertThat(capabilities.tools().listChanged()).isFalse();
      assertThat(capabilities.resources()).isNull();
      assertThat(capabilities.prompts()).isNull();
      assertThat(capabilities.completions()).isNull();
      assertThat(server.listTools()).extracting(tool -> tool.name())
          .containsExactlyInAnyOrder("local_echo", "second_echo", "third_echo", "failing_tool");
    });
  }

  @Test
  void canDisableAllMcpInfrastructure() {
    runner.withPropertyValues("opencode.mcp.enabled=false").run(context -> {
      assertThat(context).doesNotHaveBean(McpSyncServer.class);
      assertThat(context).doesNotHaveBean(McpToolScanner.class);
      assertThat(context).doesNotHaveBean(McpToolRegistry.class);
      assertThat(context).doesNotHaveBean(RemoteToolEndpointHandler.class);
      assertThat(context).doesNotHaveBean(CseRestTemplateProvider.class);
      assertThat(context).doesNotHaveBean(WebClient.class);
    });
  }

  @Test
  void normalizesEndpointWithoutLeadingSlash() {
    runner.withPropertyValues("opencode.mcp.endpoint=company-mcp").run(context -> {
      var registration = (ServletRegistrationBean<?>) context.getBean("mcpServletRegistration");
      assertThat(registration.getUrlMappings()).containsExactly("/company-mcp");
    });
  }

  @Test
  void applicationCanReplaceCseRestTemplateProvider() {
    CseRestTemplateProvider custom = RestTemplate::new;
    baseRunner.withBean(CseRestTemplateProvider.class, () -> custom).run(context -> {
      assertThat(context).hasSingleBean(CseRestTemplateProvider.class);
      assertThat(context.getBean(CseRestTemplateProvider.class)).isSameAs(custom);
    });
  }

  @Test
  void applicationCanReplaceApiFabricWebClient() {
    WebClient custom = WebClient.builder().build();
    baseRunner.withBean("apiFabricWebClient", WebClient.class, () -> custom).run(context ->
        assertThat(context.getBean("apiFabricWebClient")).isSameAs(custom));
  }

  @Test
  void createsIndependentDefaultEndpointHandlers() {
    baseRunner.run(context -> {
      assertThat(context.getBeansOfType(RemoteToolEndpointHandler.class)).hasSize(2);
      assertThat(context).hasSingleBean(ApiFabricToolEndpointHandler.class);
      assertThat(context).hasSingleBean(CseToolEndpointHandler.class);
    });
  }

  @Test
  void applicationCanReplaceOnlyApiFabricEndpointHandler() {
    RemoteToolEndpointHandler custom = new StubEndpointHandler("自定义 API Fabric");
    baseRunner.withBean(
        ApiFabricToolEndpointHandler.BEAN_NAME,
        RemoteToolEndpointHandler.class,
        () -> custom).run(context -> {
          assertThat(context.getBeansOfType(RemoteToolEndpointHandler.class)).hasSize(2);
          assertThat(context.getBean(ApiFabricToolEndpointHandler.BEAN_NAME)).isSameAs(custom);
          assertThat(context).doesNotHaveBean(ApiFabricToolEndpointHandler.class);
          assertThat(context).hasSingleBean(CseToolEndpointHandler.class);
        });
  }

  @Test
  void applicationCanReplaceOnlyCseEndpointHandler() {
    RemoteToolEndpointHandler custom = new StubEndpointHandler("自定义 CSE");
    baseRunner.withBean(
        CseToolEndpointHandler.BEAN_NAME,
        RemoteToolEndpointHandler.class,
        () -> custom).run(context -> {
          assertThat(context.getBeansOfType(RemoteToolEndpointHandler.class)).hasSize(2);
          assertThat(context.getBean(CseToolEndpointHandler.BEAN_NAME)).isSameAs(custom);
          assertThat(context).hasSingleBean(ApiFabricToolEndpointHandler.class);
          assertThat(context).doesNotHaveBean(CseToolEndpointHandler.class);
        });
  }

  @Test
  void applicationCanAddAnotherEndpointHandler() {
    RemoteToolEndpointHandler extension = new StubEndpointHandler("扩展端点");
    baseRunner.withBean(
        "extensionToolEndpointHandler",
        RemoteToolEndpointHandler.class,
        () -> extension).run(context -> {
          assertThat(context.getBeansOfType(RemoteToolEndpointHandler.class)).hasSize(3);
          assertThat(context.getBean("extensionToolEndpointHandler")).isSameAs(extension);
          assertThat(context).hasSingleBean(ApiFabricToolEndpointHandler.class);
          assertThat(context).hasSingleBean(CseToolEndpointHandler.class);
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
    FailingTools failingTools() {
      return new FailingTools();
    }

    @Bean
    RecordingAuditLogger toolAuditLogger() {
      return new RecordingAuditLogger();
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

    @Tool(name = "second_echo", description = "Return the second value", readOnly = true)
    String secondEcho() {
      return "second";
    }

    @Tool(name = "third_echo", description = "Return the third value", readOnly = true)
    String thirdEcho() {
      return "third";
    }
  }

  static class FailingTools {

    @Tool(name = "failing_tool")
    Object fail(@ToolParam(name = "secret", required = false) String secret) {
      throw new IllegalStateException("sensitive failure detail");
    }
  }

  private static final class StubEndpointHandler implements RemoteToolEndpointHandler {

    private final String endpointType;

    private StubEndpointHandler(String endpointType) {
      this.endpointType = endpointType;
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
     * 获取空的测试引用集合。
     *
     * @return 空集合
     */
    @Override
    public Set<String> references() {
      return Set.of();
    }

    /**
     * 返回测试输入注册信息。
     *
     * @param method 注解工具对应的 Java 方法
     * @param registration 工具注册信息
     * @return 原始工具注册信息
     */
    @Override
    public ToolRegistration bind(java.lang.reflect.Method method, ToolRegistration registration) {
      return registration;
    }
  }
}
