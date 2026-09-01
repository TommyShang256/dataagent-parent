package ai.opencode.dataagent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import ai.opencode.dataagent.web.tool.ApiFabricTools;
import ai.opencode.mcp.autoconfigure.McpFabricProperties;
import io.modelcontextprotocol.server.McpSyncServer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 验证 BFF 启动入口和外部化配置边界。
 *
 * @author beining.shang
 * @since 2026-09-01
 */
class DataAgentWebApplicationTest {

    @Test
    @DisplayName("启动入口委托 SpringApplication")
    void mainDelegatesToSpringApplication() {
        String[] arguments = {"--spring.main.banner-mode=off"};
        try (MockedStatic<SpringApplication> application = mockStatic(SpringApplication.class)) {
            DataAgentWebApplication.main(arguments);
            application.verify(() -> SpringApplication.run(DataAgentWebApplication.class, arguments));
        }
    }

    @Test
    @DisplayName("BFF 上下文加载 MCP 自动配置和远程工具")
    void contextLoadsMcpAndRemoteTools() {
        try (ConfigurableApplicationContext context = start("http://127.0.0.1:1/api")) {
            McpFabricProperties properties = context.getBean(McpFabricProperties.class);
            assertThat(properties.getApiFabric().getBaseUrl()).isEqualTo("http://127.0.0.1:1/api");
            assertThat(properties.getEndpoint()).isEqualTo("/rest/mcp");
            assertThat(properties.getScriptEndpoint()).isEqualTo("/rest/mcp/script");
            assertThat(context.getBean(ApiFabricTools.class)).isNotNull();
            assertThat(context.getBeansOfType(McpSyncServer.class)).hasSize(2);
            assertThat(((WebServerApplicationContext) context).getWebServer().getPort()).isPositive();
        }
    }

    @Test
    @DisplayName("应用配置只导入独立 MCP 配置文件")
    void applicationConfigOnlyImportsMcpConfig() throws IOException {
        String applicationConfig = new ClassPathResource("application.yml")
                .getContentAsString(StandardCharsets.UTF_8);
        String mcpConfig = new ClassPathResource("mcp-config.yml")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(applicationConfig)
                .contains("import: classpath:mcp-config.yml")
                .doesNotContain("dataagent:")
                .doesNotContain("opencode:");
        assertThat(mcpConfig)
                .contains("dataagent:")
                .contains("mcp:")
                .contains("api-fabric:")
                .doesNotContain("opencode:");
    }

    @Test
    @DisplayName("空 API Fabric 地址在启动期失败")
    void blankApiFabricBaseUrlFailsAtStartup() {
        assertThatThrownBy(() -> start(" "))
                .isInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("API Fabric")
                .hasStackTraceContaining("base-url");
    }

    private static ConfigurableApplicationContext start(String baseUrl) {
        return new SpringApplicationBuilder(DataAgentWebApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties(
                        "server.address=127.0.0.1",
                        "server.port=0",
                        "spring.main.banner-mode=off",
                        "spring.lifecycle.timeout-per-shutdown-phase=1s")
                .run("--dataagent.mcp.api-fabric.base-url=" + baseUrl);
    }
}
