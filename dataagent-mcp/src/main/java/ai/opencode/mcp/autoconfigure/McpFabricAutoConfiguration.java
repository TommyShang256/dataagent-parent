package ai.opencode.mcp.autoconfigure;

import ai.opencode.mcp.audit.Slf4jToolAuditLogger;
import ai.opencode.mcp.audit.ToolAuditLogger;
import ai.opencode.mcp.registry.McpToolRegistry;
import ai.opencode.mcp.remote.ApiFabricToolEndpointHandler;
import ai.opencode.mcp.remote.CseToolEndpointHandler;
import ai.opencode.mcp.remote.RemoteToolEndpointHandler;
import ai.opencode.mcp.remote.RemoteRequestHeaders;
import ai.opencode.mcp.scanner.McpToolScanner;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.web.client.RestOperations;

/**
 * 装配 Servlet MCP Server、工具目录和远程工具调用基础设施。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({McpServer.class, ServletRegistrationBean.class})
@ConditionalOnProperty(prefix = "dataagent.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(McpFabricProperties.class)
public class McpFabricAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    McpJsonMapper mcpJsonMapper(ObjectMapper objectMapper) {
        return new JacksonMcpJsonMapper(objectMapper);
    }

    @Bean(name = "agentMcpTransport", destroyMethod = "closeGracefully")
    @ConditionalOnMissingBean(name = "agentMcpTransport")
    HttpServletStreamableServerTransportProvider agentMcpTransport(
            McpJsonMapper jsonMapper, McpFabricProperties properties) {
        return transport(jsonMapper, properties, properties.getEndpoint());
    }

    @Bean(name = "scriptMcpTransport", destroyMethod = "closeGracefully")
    @ConditionalOnMissingBean(name = "scriptMcpTransport")
    HttpServletStreamableServerTransportProvider scriptMcpTransport(
            McpJsonMapper jsonMapper, McpFabricProperties properties) {
        return transport(jsonMapper, properties, properties.getScriptEndpoint());
    }

    private static HttpServletStreamableServerTransportProvider transport(
            McpJsonMapper jsonMapper,
            McpFabricProperties properties,
            String endpoint) {
        HttpServletStreamableServerTransportProvider.Builder builder =
                HttpServletStreamableServerTransportProvider.builder()
                        .jsonMapper(jsonMapper)
                        .mcpEndpoint(normalizeEndpoint(endpoint))
                        .contextExtractor(new RemoteRequestHeaders())
                        .maxRequestSize(Math.toIntExact(properties.getMaxRequestSize().toBytes()));
        if (properties.getKeepAlive() != null) {
            builder.keepAliveInterval(properties.getKeepAlive());
        }
        return builder.build();
    }

    @Bean(name = "agentMcpServletRegistration")
    @ConditionalOnMissingBean(name = "agentMcpServletRegistration")
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> agentMcpServletRegistration(
            @Qualifier("agentMcpTransport") HttpServletStreamableServerTransportProvider transport,
            McpFabricProperties properties) {
        String endpoint = normalizeEndpoint(properties.getEndpoint());
        requireDistinctEndpoints(endpoint, normalizeEndpoint(properties.getScriptEndpoint()));
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(transport, endpoint);
        registration.setName("dataagentAgentMcpServlet");
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean(name = "scriptMcpServletRegistration")
    @ConditionalOnMissingBean(name = "scriptMcpServletRegistration")
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> scriptMcpServletRegistration(
            @Qualifier("scriptMcpTransport") HttpServletStreamableServerTransportProvider transport,
            McpFabricProperties properties) {
        String endpoint = normalizeEndpoint(properties.getScriptEndpoint());
        requireDistinctEndpoints(normalizeEndpoint(properties.getEndpoint()), endpoint);
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(transport, endpoint);
        registration.setName("dataagentScriptMcpServlet");
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean(name = "agentMcpServer", destroyMethod = "closeGracefully")
    @ConditionalOnMissingBean(name = "agentMcpServer")
    McpSyncServer agentMcpServer(
            @Qualifier("agentMcpTransport") HttpServletStreamableServerTransportProvider transport,
            McpJsonMapper jsonMapper,
            McpFabricProperties properties) {
        return server(transport, jsonMapper, properties);
    }

    @Bean(name = "scriptMcpServer", destroyMethod = "closeGracefully")
    @ConditionalOnMissingBean(name = "scriptMcpServer")
    McpSyncServer scriptMcpServer(
            @Qualifier("scriptMcpTransport") HttpServletStreamableServerTransportProvider transport,
            McpJsonMapper jsonMapper,
            McpFabricProperties properties) {
        return server(transport, jsonMapper, properties);
    }

    private static McpSyncServer server(
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
    @ConditionalOnMissingBean(name = "apiFabricWebClient")
    WebClient apiFabricWebClient() {
        return WebClient.builder().build();
    }

    @Bean
    @ConditionalOnMissingBean(name = ApiFabricToolEndpointHandler.BEAN_NAME)
    ApiFabricToolEndpointHandler apiFabricToolEndpointHandler(
            McpFabricProperties properties,
            ObjectMapper objectMapper,
            @Qualifier("apiFabricWebClient") WebClient apiFabricClient) {
        return new ApiFabricToolEndpointHandler(properties, objectMapper, apiFabricClient);
    }

    @Bean
    @ConditionalOnMissingBean(name = CseToolEndpointHandler.BEAN_NAME)
    CseToolEndpointHandler cseToolEndpointHandler(
            McpFabricProperties properties,
            ObjectMapper objectMapper,
            @Qualifier("cseRestOperations") ObjectProvider<RestOperations> cseClient) {
        return new CseToolEndpointHandler(properties, objectMapper, cseClient.getIfAvailable());
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
            @Qualifier("agentMcpServer") McpSyncServer agentServer,
            @Qualifier("scriptMcpServer") McpSyncServer scriptServer,
            ToolAuditLogger auditLogger) {
        return new McpToolRegistry(scanner, objectMapper, agentServer, scriptServer, auditLogger);
    }

    private static String normalizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("MCP endpoint must not be blank");
        }
        return endpoint.startsWith("/") ? endpoint : "/" + endpoint;
    }

    private static void requireDistinctEndpoints(String agentEndpoint, String scriptEndpoint) {
        if (agentEndpoint.equals(scriptEndpoint)) {
            throw new IllegalArgumentException("MCP Agent and Script endpoints must be different: " + agentEndpoint);
        }
    }
}
