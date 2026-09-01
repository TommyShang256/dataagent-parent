package ai.opencode.dataagent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 使用标准 MCP Java 客户端验证 BFF 到 API Fabric 的完整调用链。
 *
 * @author beining.shang
 * @since 2026-09-01
 */
class DataAgentMcpIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Path RUNNER = Path.of("..", "dataagent-runner", "bin", "dataagent-runner")
            .toAbsolutePath()
            .normalize();

    @TempDir
    Path temporaryDirectory;

    private final AtomicReference<CapturedRequest> orderRequest = new AtomicReference<>();

    private final AtomicReference<CapturedRequest> uploadRequest = new AtomicReference<>();

    private final AtomicInteger uploadCalls = new AtomicInteger();

    private final AtomicInteger validateCalls = new AtomicInteger();

    private HttpServer apiFabric;

    private ConfigurableApplicationContext application;

    private McpSyncClient agentClient;

    private McpSyncClient scriptClient;

    private int bffPort;

    @BeforeEach
    void startServers() throws IOException {
        apiFabric = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        apiFabric.createContext("/api/orders/O-1", exchange -> capture(
                exchange, orderRequest, "{\"id\":\"O-1\",\"status\":\"created\"}", "application/json"));
        apiFabric.createContext("/api/tables", exchange -> {
            uploadCalls.incrementAndGet();
            capture(exchange, uploadRequest, "uploaded", "text/plain");
        });
        apiFabric.createContext("/api/tables/validate", exchange -> {
            validateCalls.incrementAndGet();
            capture(exchange, new AtomicReference<>(), "validated", "text/plain");
        });
        apiFabric.start();

        application = new SpringApplicationBuilder(DataAgentWebApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties(
                        "server.address=127.0.0.1",
                        "server.port=0",
                        "spring.main.banner-mode=off",
                        "spring.lifecycle.timeout-per-shutdown-phase=1s")
                .run("--dataagent.mcp.api-fabric.base-url=http://127.0.0.1:"
                        + apiFabric.getAddress().getPort() + "/api");
        bffPort = ((WebServerApplicationContext) application).getWebServer().getPort();
        agentClient = client(bffPort, "/rest/mcp");
        scriptClient = client(bffPort, "/rest/mcp/script");
    }

    @AfterEach
    void stopServers() {
        if (agentClient != null) {
            agentClient.closeGracefully();
        }
        if (scriptClient != null) {
            scriptClient.closeGracefully();
        }
        if (application != null) {
            application.close();
        }
        if (apiFabric != null) {
            apiFabric.stop(0);
        }
    }

    @Test
    @DisplayName("标准 MCP 客户端初始化并发现 BFF 工具")
    void standardClientInitializesAndListsTools() {
        McpSchema.ListToolsResult result = agentClient.listTools();
        McpSchema.ListToolsResult scriptResult = scriptClient.listTools();
        assertThat(agentClient.isInitialized()).isTrue();
        assertThat(scriptClient.isInitialized()).isTrue();
        assertThat(agentClient.getServerInfo().name()).isEqualTo("dataagent-web");
        assertThat(scriptClient.getServerInfo().name()).isEqualTo("dataagent-web");
        assertThat(result.tools()).extracting(McpSchema.Tool::name)
                .containsExactlyInAnyOrder("create_order", "upload_table");
        assertThat(scriptResult.tools()).extracting(McpSchema.Tool::name)
                .containsExactlyInAnyOrder("upload_table", "validate_table");

        McpSchema.Tool order = tool(result, "create_order");
        assertThat(properties(order)).containsKeys("orderId", "verbose", "headerA", "A", "customerId");
        assertThat(properties(order)).doesNotContainKey("bodyA");
        McpSchema.Tool upload = tool(result, "upload_table");
        assertThat(properties(upload)).containsKeys("filePath", "catalog", "description");
        assertThat(properties(upload).get("filePath")).isInstanceOf(Map.class);
        assertThat(order.meta().get("ai.opencode.dataagent/allowed-callers"))
                .isEqualTo(List.of("agent"));
        assertThat(upload.meta().get("ai.opencode.dataagent/allowed-callers"))
                .isEqualTo(List.of("agent", "script"));
        assertThat(tool(scriptResult, "validate_table").meta().get("ai.opencode.dataagent/allowed-callers"))
                .isEqualTo(List.of("script"));
    }

    @Test
    @DisplayName("JSON 工具隔离同名 Header 与 Body 并返回远程结果")
    void jsonToolSeparatesSameNamedHeaderAndBody() throws Exception {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("orderId", "O-1");
        arguments.put("verbose", true);
        arguments.put("headerA", "header-value");
        arguments.put("A", "body-value");
        arguments.put("customerId", "C-1");

        McpSchema.CallToolResult result = agentClient.callTool(
                new McpSchema.CallToolRequest("create_order", arguments));
        assertThat(result.isError()).isFalse();
        assertThat(((McpSchema.TextContent) result.content().getFirst()).text())
                .contains("O-1", "created");

        CapturedRequest captured = orderRequest.get();
        assertThat(captured).isNotNull();
        assertThat(captured.method()).isEqualTo("POST");
        assertThat(captured.uri()).isEqualTo("/api/orders/O-1?verbose=true");
        assertThat(captured.headerA()).isEqualTo("header-value");
        assertThat(captured.contentType()).isEqualTo("application/json");
        JsonNode body = OBJECT_MAPPER.readTree(captured.body());
        assertThat(body.path("A").asText()).isEqualTo("body-value");
        assertThat(body.path("customerId").asText()).isEqualTo("C-1");
        assertThat(body.has("headerA")).isFalse();
    }

    @Test
    @DisplayName("multipart 工具上传文件并携带 RequestParam 和文本 RequestPart")
    void multipartToolUploadsFileAndRegularParts() throws IOException {
        Path uploadFile = Files.writeString(temporaryDirectory.resolve("table.dsl"), "create table demo");
        Map<String, Object> arguments = Map.of(
                "filePath", uploadFile.toString(),
                "catalog", "analytics",
                "description", "integration upload");

        McpSchema.CallToolResult result = agentClient.callTool(
                new McpSchema.CallToolRequest("upload_table", arguments));
        assertThat(result.isError()).isFalse();
        assertThat(((McpSchema.TextContent) result.content().getFirst()).text()).contains("uploaded");

        CapturedRequest captured = uploadRequest.get();
        assertThat(captured).isNotNull();
        assertThat(captured.method()).isEqualTo("POST");
        assertThat(captured.uri()).isEqualTo("/api/tables");
        assertThat(captured.contentType()).startsWith("multipart/form-data;boundary=");
        assertThat(captured.body()).contains(
                "name=\"dsl\"", "filename=\"table.dsl\"", "create table demo",
                "name=\"catalog\"", "analytics",
                "name=\"description\"", "integration upload");
        assertThat(uploadCalls).hasValue(1);
    }

    @Test
    @DisplayName("不存在的上传文件不会请求 API Fabric")
    void missingUploadFileDoesNotCallApiFabric() {
        Path missing = temporaryDirectory.resolve("missing.dsl");
        McpSchema.CallToolResult result = agentClient.callTool(new McpSchema.CallToolRequest(
                "upload_table",
                Map.of("filePath", missing.toString(), "catalog", "analytics")));

        assertThat(result.isError()).isTrue();
        assertThat(((McpSchema.TextContent) result.content().getFirst()).text())
                .contains("upload_table", "filePath", "does not exist");
        assertThat(uploadCalls).hasValue(0);
        assertThat(uploadRequest).hasValue(null);
    }

    @Test
    @DisplayName("Script 可调用共享和 Script-only 工具")
    void scriptCallsSharedAndScriptOnlyTools() throws IOException {
        Path uploadFile = Files.writeString(temporaryDirectory.resolve("script.dsl"), "create table script_demo");
        McpSchema.CallToolResult upload = scriptClient.callTool(scriptRequest(
                "upload_table",
                Map.of("filePath", uploadFile.toString(), "catalog", "script")));
        McpSchema.CallToolResult validate = scriptClient.callTool(scriptRequest(
                "validate_table",
                Map.of("catalog", "script")));

        assertThat(upload.isError()).isFalse();
        assertThat(validate.isError()).isFalse();
        assertThat(uploadCalls).hasValue(1);
        assertThat(validateCalls).hasValue(1);
    }

    @Test
    @DisplayName("Python Runner 支持基础 CLI 并在连接前拒绝无效输入")
    void pythonRunnerSupportsCliAndRejectsInvalidInput() throws Exception {
        RunnerResult version = runRunner(false, "--version");
        RunnerResult help = runRunner(false, "--help");
        RunnerResult missingEnvironment = runRunner(false, "--list");
        RunnerResult invalidArguments = runRunner(false, "validate_table", "[]");

        assertThat(version.exitCode()).as(version.standardError()).isZero();
        assertThat(version.standardOutput()).contains("0.1.0-SNAPSHOT");
        assertThat(help.exitCode()).as(help.standardError()).isZero();
        assertThat(help.standardOutput()).contains("dataagent-runner --list");
        assertThat(missingEnvironment.exitCode()).isEqualTo(3);
        assertThat(missingEnvironment.standardError()).contains("POD_IP must be configured");
        assertThat(invalidArguments.exitCode()).isEqualTo(2);
        assertThat(invalidArguments.standardError()).contains("Tool arguments must be a JSON object");
    }

    @Test
    @DisplayName("Python Runner 初始化会话并查询隔离的 Script 工具目录")
    void pythonRunnerListsScriptTools() throws Exception {
        RunnerResult result = runRunner(true, "--list");

        assertThat(result.exitCode()).as(result.standardError()).isZero();
        assertThat(OBJECT_MAPPER.readTree(result.standardOutput()))
                .extracting(node -> node.path("name").asText())
                .containsExactly("upload_table", "validate_table");
    }

    @Test
    @DisplayName("Python Runner 通过标准 MCP 流程调用 BFF 的 Script-only 工具")
    void pythonRunnerCallsScriptToolEndToEnd() throws Exception {
        RunnerResult result = runRunner(true, "validate_table", "{\"catalog\":\"runner\"}");

        assertThat(result.exitCode()).as(result.standardError()).isZero();
        assertThat(OBJECT_MAPPER.readTree(result.standardOutput()).path("isError").asBoolean()).isFalse();
        assertThat(result.standardOutput()).contains("validated");
        assertThat(validateCalls).hasValue(1);
    }

    @Test
    @DisplayName("Agent 与 Script 越权均在 API Fabric 前拒绝")
    void callerPolicyRejectsBeforeApiFabric() {
        assertThatThrownBy(() -> agentClient.callTool(
                new McpSchema.CallToolRequest("validate_table", Map.of("catalog", "agent"))))
                .hasMessageContaining("Unknown tool", "validate_table");
        assertThatThrownBy(() -> scriptClient.callTool(scriptRequest(
                "create_order",
                Map.of("orderId", "O-1", "verbose", true, "headerA", "header", "A", "body"))))
                .hasMessageContaining("Unknown tool", "create_order");
        assertThat(validateCalls).hasValue(0);
        assertThat(orderRequest).hasValue(null);
    }

    private static McpSchema.CallToolRequest scriptRequest(String name, Map<String, Object> arguments) {
        return new McpSchema.CallToolRequest(name, arguments);
    }

    private RunnerResult runRunner(boolean configureEndpoint, String... arguments) throws Exception {
        assertThat(RUNNER).isRegularFile().isExecutable();
        List<String> command = new ArrayList<>();
        command.add(RUNNER.toString());
        command.addAll(List.of(arguments));
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        if (configureEndpoint) {
            processBuilder.environment().put("POD_IP", "127.0.0.1");
            processBuilder.environment().put("POD_PORT", Integer.toString(bffPort));
        } else {
            processBuilder.environment().remove("POD_IP");
            processBuilder.environment().remove("POD_PORT");
        }
        Process process = processBuilder.start();
        String standardOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String standardError = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new RunnerResult(process.waitFor(), standardOutput, standardError);
    }

    private static McpSyncClient client(int port, String endpoint) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + port)
                .endpoint(endpoint)
                .build();
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(10))
                .initializationTimeout(Duration.ofSeconds(10))
                .build();
        client.initialize();
        return client;
    }

    private static McpSchema.Tool tool(McpSchema.ListToolsResult result, String name) {
        return result.tools().stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(McpSchema.Tool tool) {
        return (Map<String, Object>) tool.inputSchema().get("properties");
    }

    private static void capture(
            HttpExchange exchange,
            AtomicReference<CapturedRequest> target,
            String responseBody,
            String responseType) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        target.set(new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().toString(),
                exchange.getRequestHeaders().getFirst("A"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                body));
        byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", responseType);
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private record CapturedRequest(
            String method,
            String uri,
            String headerA,
            String contentType,
            String body) {
    }

    private record RunnerResult(int exitCode, String standardOutput, String standardError) {
    }
}
