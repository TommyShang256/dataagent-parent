package ai.opencode.mcp.autoconfigure;

import ai.opencode.mcp.audit.Slf4jToolAuditLogger;
import ai.opencode.mcp.audit.ToolAuditLogger;
import ai.opencode.mcp.registry.McpToolRegistry;
import ai.opencode.mcp.remote.ApiFabricToolEndpointHandler;
import ai.opencode.mcp.remote.CseToolEndpointHandler;
import ai.opencode.mcp.remote.RemoteToolEndpointHandler;
import ai.opencode.mcp.remote.RemoteToolWebClientProvider;
import ai.opencode.mcp.remote.ServletToolContextExtractor;
import ai.opencode.mcp.scanner.McpToolScanner;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 装配 Servlet MCP Server、工具目录和远程工具调用基础设施。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({McpServer.class, ServletRegistrationBean.class})
@ConditionalOnProperty(prefix = "opencode.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(McpFabricProperties.class)
public class McpFabricAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  McpJsonMapper mcpJsonMapper(ObjectMapper objectMapper) {
    return new JacksonMcpJsonMapper(objectMapper);
  }

  @Bean(destroyMethod = "closeGracefully")
  @ConditionalOnMissingBean
  HttpServletStreamableServerTransportProvider mcpTransport(
      McpJsonMapper jsonMapper, McpFabricProperties properties) {
    HttpServletStreamableServerTransportProvider.Builder builder =
        HttpServletStreamableServerTransportProvider.builder()
        .jsonMapper(jsonMapper)
        .mcpEndpoint(normalizeEndpoint(properties.getEndpoint()))
        .contextExtractor(new ServletToolContextExtractor())
        .maxRequestSize(Math.toIntExact(properties.getMaxRequestSize().toBytes()));
    if (properties.getKeepAlive() != null) {
      builder.keepAliveInterval(properties.getKeepAlive());
    }
    return builder.build();
  }

  @Bean
  @ConditionalOnMissingBean(name = "mcpServletRegistration")
  ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServletRegistration(
      HttpServletStreamableServerTransportProvider transport, McpFabricProperties properties) {
    String endpoint = normalizeEndpoint(properties.getEndpoint());
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
        new ServletRegistrationBean<>(transport, endpoint);
    registration.setName("opencodeMcpServlet");
    registration.setAsyncSupported(true);
    registration.setLoadOnStartup(1);
    return registration;
  }

  @Bean(destroyMethod = "closeGracefully")
  @ConditionalOnMissingBean
  McpSyncServer mcpServer(
      HttpServletStreamableServerTransportProvider transport,
      McpJsonMapper jsonMapper,
      McpFabricProperties properties) {
    return McpServer.sync(transport)
        .jsonMapper(jsonMapper)
        .serverInfo(properties.getServerName(), properties.getServerVersion())
        .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
        .requestTimeout(properties.getRequestTimeout())
        .build();
  }

  @Bean
  @ConditionalOnMissingBean
  ToolAuditLogger toolAuditLogger() {
    return new Slf4jToolAuditLogger();
  }

  @Bean
  @ConditionalOnMissingBean
  RemoteToolWebClientProvider remoteToolWebClientProvider() {
    WebClient client = WebClient.builder().build();
    return type -> client;
  }

  @Bean
  @ConditionalOnMissingBean(name = ApiFabricToolEndpointHandler.BEAN_NAME)
  ApiFabricToolEndpointHandler apiFabricToolEndpointHandler(
      McpFabricProperties properties, ObjectMapper objectMapper, RemoteToolWebClientProvider provider) {
    return new ApiFabricToolEndpointHandler(properties, objectMapper, provider);
  }

  @Bean
  @ConditionalOnMissingBean(name = CseToolEndpointHandler.BEAN_NAME)
  CseToolEndpointHandler cseToolEndpointHandler(
      McpFabricProperties properties, ObjectMapper objectMapper, RemoteToolWebClientProvider provider) {
    return new CseToolEndpointHandler(properties, objectMapper, provider);
  }

  @Bean
  @ConditionalOnMissingBean
  McpToolScanner mcpToolScanner(
      ConfigurableListableBeanFactory beanFactory,
      ObjectMapper objectMapper,
      List<RemoteToolEndpointHandler> endpointHandlers) {
    return new McpToolScanner(beanFactory, objectMapper, endpointHandlers);
  }

  @Bean
  @ConditionalOnMissingBean
  McpToolRegistry mcpToolRegistry(
      McpToolScanner scanner,
      ObjectMapper objectMapper,
      McpSyncServer server,
      ToolAuditLogger auditLogger) {
    return new McpToolRegistry(scanner, objectMapper, server, auditLogger);
  }

  private static String normalizeEndpoint(String endpoint) {
    if (endpoint == null || endpoint.isBlank()) {
      throw new IllegalArgumentException("MCP endpoint must not be blank");
    }
    return endpoint.startsWith("/") ? endpoint : "/" + endpoint;
  }
}
