package ai.opencode.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.opencode.mcp.annotation.Tool;
import ai.opencode.mcp.annotation.ToolParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 使用 opencode MCP client 验证真实 HTTP API Fabric 调用链。
 *
 * @author beining.shang
 * @since 2026-09-01
 */
class ApiFabricOpenCodeE2eTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String CLIENT_SCRIPT = """
            import { ConfigMCP } from "@opencode-ai/schema/config/mcp"
            import { MCPClient } from "@opencode-ai/core/mcp/client"
            import { Effect } from "effect"

            const url = process.env.MCP_E2E_URL
            if (!url) throw new Error("MCP_E2E_URL is required")

            const result = await Effect.runPromise(Effect.scoped(Effect.gen(function* () {
              const connection = yield* MCPClient.connect(
                "dataagent-mcp",
                new ConfigMCP.Remote({
                  type: "remote",
                  url,
                  oauth: false,
                  headers: { "X-Trace-Id": "opencode-e2e" },
                }),
                process.cwd(),
              )
              const tools = yield* connection.tools()
              const call = yield* connection.callTool({
                name: "create_order",
                args: {
                  orderId: "O-1",
                  verbose: true,
                  bizMode: "preview",
                  customerId: "C-1",
                },
              })
              return { tools: tools.map((tool) => tool.name), call }
            })))

            process.stdout.write(JSON.stringify(result))
            """;

    @Test
    void opencodeClientInvokesApiFabricThroughStandardMcpRequests() throws Exception {
        Path opencodeRepository = opencodeRepository();
        assumeTrue(Files.isRegularFile(opencodeRepository.resolve("packages/core/package.json")),
                "Set -Dopencode.repository to an opencode V2 checkout");
        assumeTrue(commandSucceeds("bun", "--version"), "Bun is required for the opencode client test");

        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        HttpServer apiFabric = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        apiFabric.createContext("/api/orders/O-1", exchange -> handleApiFabric(exchange, captured));
        apiFabric.start();

        try (ConfigurableApplicationContext application = startMcpServer(apiFabric.getAddress().getPort())) {
            int mcpPort = ((WebServerApplicationContext) application).getWebServer().getPort();
            JsonNode clientResult = runOpenCodeClient(opencodeRepository, mcpPort);

            assertThat(clientResult.path("tools").toString()).contains("\"create_order\"");
            assertThat(clientResult.path("call").path("isError").asBoolean()).isFalse();
            assertThat(clientResult.path("call").path("content").get(0).path("type").asText()).isEqualTo("text");
            assertThat(OBJECT_MAPPER.readTree(
                    clientResult.path("call").path("content").get(0).path("text").asText()))
                    .isEqualTo(OBJECT_MAPPER.readTree("{\"id\":\"O-1\",\"status\":\"created\"}"));

            CapturedRequest request = captured.get();
            assertThat(request).isNotNull();
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.uri()).isEqualTo("/api/orders/O-1?verbose=true");
            assertThat(request.headers().traceId()).isEqualTo("opencode-e2e");
            assertThat(request.headers().bizMode()).isEqualTo("preview");
            assertThat(request.headers().contentType()).isEqualTo("application/json");
            assertThat(OBJECT_MAPPER.readTree(request.body()))
                    .isEqualTo(OBJECT_MAPPER.readTree("{\"customerId\":\"C-1\"}"));
        } finally {
            apiFabric.stop(0);
        }
    }

    private static ConfigurableApplicationContext startMcpServer(int apiFabricPort) {
        return new SpringApplicationBuilder(TestApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties(
                        "server.address=127.0.0.1",
                        "server.port=0",
                        "spring.main.banner-mode=off",
                        "spring.lifecycle.timeout-per-shutdown-phase=1s",
                        "opencode.mcp.api-fabric.base-url=http://127.0.0.1:" + apiFabricPort + "/api",
                        "opencode.mcp.api-fabric.endpoints.create_order.method=POST",
                        "opencode.mcp.api-fabric.endpoints.create_order.path-template=/orders/{orderId}",
                        "opencode.mcp.api-fabric.endpoints.create_order.query.verbose=verbose",
                        "opencode.mcp.api-fabric.endpoints.create_order.headers.business.X-Biz-Mode=bizMode")
                .run();
    }

    private static JsonNode runOpenCodeClient(Path repository, int mcpPort) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("bun", "-e", CLIENT_SCRIPT);
        builder.directory(repository.resolve("packages/core").toFile());
        builder.environment().put("MCP_E2E_URL", "http://127.0.0.1:" + mcpPort + "/rest/mcp");
        Process process = builder.start();
        boolean completed = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new IllegalStateException("OpenCode MCP client timed out");
        }
        String standardOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String errorOutput = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(errorOutput).isZero();
        return OBJECT_MAPPER.readTree(standardOutput);
    }

    private static void handleApiFabric(
            HttpExchange exchange,
            AtomicReference<CapturedRequest> captured) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        captured.set(new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().toString(),
                new CapturedHeaders(
                        exchange.getRequestHeaders().getFirst("X-Trace-Id"),
                        exchange.getRequestHeaders().getFirst("X-Biz-Mode"),
                        exchange.getRequestHeaders().getFirst("Content-Type")),
                body));
        byte[] response = "{\"id\":\"O-1\",\"status\":\"created\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static Path opencodeRepository() {
        String configured = System.getProperty("opencode.repository");
        return configured == null || configured.isBlank()
                ? Path.of("..", "opencode").toAbsolutePath().normalize()
                : Path.of(configured).toAbsolutePath().normalize();
    }

    private static boolean commandSucceeds(String command, String argument) {
        try {
            Process process = new ProcessBuilder(command, argument).start();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private record CapturedRequest(String method, String uri, CapturedHeaders headers, String body) {
    }

    private record CapturedHeaders(String traceId, String bizMode, String contentType) {
    }

    private record OrderResponse(String id, String status) {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        ApiFabricTools apiFabricTools() {
            return new ApiFabricTools();
        }
    }

    static class ApiFabricTools {

        @Tool(name = "create_order", description = "Create an order")
        OrderResponse createOrder(
                String orderId,
                boolean verbose,
                String bizMode,
                @ToolParam(required = false) String customerId) {
            throw new IllegalStateException("Remote proxy method must not execute");
        }
    }
}
