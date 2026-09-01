package ai.opencode.mcp.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.opencode.mcp.annotation.Tool;
import ai.opencode.mcp.annotation.ToolParam;
import ai.opencode.mcp.api.ToolRegistration;
import ai.opencode.mcp.autoconfigure.McpFabricProperties;
import ai.opencode.mcp.scanner.McpToolScanner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Mono;

/**
 * 验证 API Fabric 与 CSE 端点处理器的完整请求映射行为。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
class RemoteToolEndpointHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void isolatesApiFabricAndCseClientDependencies() {
        List<Class<?>> apiFabricDependencies = Arrays.stream(
                        ApiFabricToolEndpointHandler.class.getDeclaredFields())
                .map(Field::getType)
                .toList();
        List<Class<?>> cseDependencies = Arrays.stream(
                        CseToolEndpointHandler.class.getDeclaredFields())
                .map(Field::getType)
                .toList();

        assertThat(apiFabricDependencies)
                .contains(WebClient.class)
                .doesNotContain(RestOperations.class);
        assertThat(cseDependencies)
                .contains(RestOperations.class)
                .doesNotContain(WebClient.class);
    }

    @Test
    void bindsFabricRequestWithAutomaticPathBodyQueryAndHeaderRules() throws Exception {
        var capture = new CaptureExchange("{\"id\":\"O-1\",\"status\":\"created\"}", HttpStatus.OK);
        var properties = validProperties();
        var tools = scan(properties, capture, new ProxyTools());

        var remote = tools.stream().filter(tool -> tool.name().equals("create_order")).findFirst().orElseThrow();
        assertThat(remote.type()).isEqualTo(Tool.Type.API_FABRIC);
        assertThat(remote.readOnly()).isTrue();
        assertThat(remote.destructive()).isFalse();
        assertThat(remote.idempotent()).isTrue();
        assertThat(remote.openWorld()).isFalse();
        Map<String, List<String>> headers = Map.of(
                "Authorization", List.of("Bearer one", "Bearer two"),
                "x-biz-mode", List.of("inbound"),
                "Host", List.of("attacker"));
        var result = remote.invoker().invoke(Map.of(
                "tenantId", "tenant/a",
                "tags", List.of("new", "priority"),
                "bizMode", "preview",
                "customerId", "C-1",
                "lines", List.of(Map.of("sku", "SKU-1", "quantity", 2, "price", new BigDecimal("3.50")))), headers);

        assertThat(result).isEqualTo(new OrderResponse("O-1", "created"));
        assertThat(capture.method).isEqualTo(HttpMethod.POST);
        assertThat(capture.uri.toString()).isEqualTo(
                "https://fabric.example/base/tenants/tenant%2Fa/orders?tag=new&tag=priority");
        assertThat(capture.headers.get("Authorization")).containsExactly("Bearer one", "Bearer two");
        assertThat(capture.headers.get("X-Biz-Mode")).containsExactly("preview");
        assertThat(capture.headers).doesNotContainKeys("Host");
        assertThat(mapper.readTree(capture.body)).isEqualTo(mapper.readTree(
                "{\"customerId\":\"C-1\",\"lines\":[{\"sku\":\"SKU-1\",\"quantity\":2,\"price\":3.50}]}"));
    }

    @Test
    void preservesCseSchemeAndConvertsGenericResponseWithoutExecutingProxyBody() throws Exception {
        CaptureExchange fabric = new CaptureExchange("{}", HttpStatus.OK);
        CaptureRestOperations capture =
                new CaptureRestOperations("[\"reserved\",\"SKU-1\"]", HttpStatus.OK);
        ToolRegistration tool = scan(validProperties(), fabric, capture, new ProxyTools()).stream()
                .filter(item -> item.name().equals("reserve_inventory")).findFirst().orElseThrow();

        assertThat(tool.type()).isEqualTo(Tool.Type.CSE);
        assertThat(tool.invoker().invoke(Map.of("warehouseId", "W 1", "sku", "SKU-1")))
                .isEqualTo(List.of("reserved", "SKU-1"));
        assertThat(capture.method).isEqualTo(HttpMethod.POST);
        assertThat(capture.uri.toString()).isEqualTo("cse://inventory-service/warehouses/W%201/reservations");
        assertThat(capture.headers.getContentType()).isEqualTo(org.springframework.http.MediaType.APPLICATION_JSON);
        JsonNode capturedBody = mapper.valueToTree(capture.body);
        assertThat(capturedBody).isEqualTo(mapper.readTree("{\"sku\":\"SKU-1\"}"));
    }

    @Test
    void failsBeforePublishingCseToolWhenRestTemplateIsNotConfigured() {
        assertThatThrownBy(() -> scan(
                validProperties(), new CaptureExchange("{}", HttpStatus.OK), null, new ProxyTools()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CSE RestTemplate", "cseRestOperations");
    }

    @Test
    void mapsCseStatusAndResponseBodyToToolError() {
        CaptureRestOperations capture =
                new CaptureRestOperations("inventory unavailable", HttpStatus.SERVICE_UNAVAILABLE);
        ToolRegistration tool = tool(scan(
                        validProperties(), new CaptureExchange("{}", HttpStatus.OK), capture, new ProxyTools()),
                "reserve_inventory");

        assertThatThrownBy(() -> tool.invoker().invoke(Map.of("warehouseId", "W", "sku", "SKU-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reserve_inventory", "503", "inventory unavailable");
    }

    @Test
    void leavesUnmatchedAnnotationToolLocalAndMapsDownstreamErrors() throws Exception {
        var capture = new CaptureExchange("unavailable", HttpStatus.SERVICE_UNAVAILABLE);
        var tools = scan(validProperties(), capture, new ProxyTools());
        var local = tools.stream().filter(tool -> tool.name().equals("echo")).findFirst().orElseThrow();
        var remote = tools.stream().filter(tool -> tool.name().equals("create_order")).findFirst().orElseThrow();

        assertThat(local.type()).isEqualTo(Tool.Type.LOCAL);
        assertThat(local.invoker().invoke(Map.of("message", "ok"))).isEqualTo("ok");
        assertThatThrownBy(() -> remote.invoker().invoke(Map.of(
                "tenantId", "T", "tags", List.of(), "bizMode", "x", "customerId", "C", "lines", List.of())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("create_order", "503", "unavailable");
    }

    @Test
    void validatesWholeCatalogBeforeReturningAnyRegistration() {
        var duplicate = validProperties();
        var cse = new McpFabricProperties.CseEndpoint();
        cse.setMethod("POST");
        cse.setUriTemplate("cse://service/items/{tenantId}");
        duplicate.getCse().getEndpoints().put("create_order", cse);
        assertFailure(duplicate, "endpoint", "create_order", "API Fabric", "CSE");

        var unknown = validProperties();
        var endpoint = new McpFabricProperties.ApiFabricEndpoint();
        endpoint.setMethod("GET");
        endpoint.setPathTemplate("/unknown");
        unknown.getApiFabric().getEndpoints().put("unknown_ref", endpoint);
        assertFailure(unknown, "API Fabric", "unknown_ref", "no matching");

        var missing = validProperties();
        missing.getApiFabric().getEndpoints().get("create_order").setPathTemplate("/orders/{missing}");
        assertFailure(missing, "API Fabric", "create_order", "missing", "Path");

        var conflict = validProperties();
        conflict.getApiFabric().getEndpoints().get("create_order").getQuery().put("tenant", "tenantId");
        assertFailure(conflict, "API Fabric", "create_order", "tenantId", "Path", "Query");

        var unknownParameter = validProperties();
        unknownParameter.getApiFabric().getEndpoints().get("create_order").getQuery().put("unknown", "missingArg");
        assertFailure(unknownParameter, "API Fabric", "create_order", "missingArg", "Query");

        var duplicateDownstream = validProperties();
        duplicateDownstream.getApiFabric().getEndpoints().get("create_order").getQuery().put("TAG", "customerId");
        assertFailure(duplicateDownstream, "API Fabric", "create_order", "Query", "duplicated");

        var queryHeaderConflict = validProperties();
        queryHeaderConflict.getApiFabric().getEndpoints().get("create_order").getHeaders().getBusiness()
                .put("X-Tag-Mode", "tags");
        assertFailure(queryHeaderConflict, "API Fabric", "create_order", "tags", "Query", "business header");

        var restricted = validProperties();
        restricted.getApiFabric().getEndpoints().get("create_order").getHeaders().getBusiness()
                .put("Content-Length", "bizMode");
        assertFailure(restricted, "API Fabric", "create_order", "Content-Length", "restricted system header");

        var invalidMethod = validProperties();
        invalidMethod.getApiFabric().getEndpoints().get("create_order").setMethod("FETCH");
        assertFailure(invalidMethod, "API Fabric", "create_order", "method", "FETCH");

        McpFabricProperties absolutePath = validProperties();
        absolutePath.getApiFabric().getEndpoints().get("create_order")
                .setPathTemplate("https://other.example/orders");
        assertFailure(absolutePath, "API Fabric", "create_order", "path-template", "exactly one /");

        McpFabricProperties relativePath = validProperties();
        relativePath.getApiFabric().getEndpoints().get("create_order")
                .setPathTemplate("orders/{tenantId}");
        assertFailure(relativePath, "API Fabric", "create_order", "path-template", "exactly one /");

        McpFabricProperties schemeRelativePath = validProperties();
        schemeRelativePath.getApiFabric().getEndpoints().get("create_order")
                .setPathTemplate("//other.example/orders/{tenantId}");
        assertFailure(schemeRelativePath, "API Fabric", "create_order", "path-template", "exactly one /");

        McpFabricProperties invalidCseScheme = validProperties();
        invalidCseScheme.getCse().getEndpoints().get("reserve_inventory")
                .setUriTemplate("https://inventory-service/warehouses/{warehouseId}/reservations");
        assertFailure(invalidCseScheme, "CSE", "reserve_inventory", "cse://service-name");

        McpFabricProperties missingCseService = validProperties();
        missingCseService.getCse().getEndpoints().get("reserve_inventory")
                .setUriTemplate("cse:///warehouses/{warehouseId}/reservations");
        assertFailure(missingCseService, "CSE", "reserve_inventory", "cse://service-name");
    }

    @Test
    void omitsMissingOptionalBodyButKeepsExplicitNullAndSendsNoBodyWhenAllConsumed() throws Exception {
        var properties = validProperties();
        var endpoint = new McpFabricProperties.ApiFabricEndpoint();
        endpoint.setMethod("GET");
        endpoint.setPathTemplate("/lookup/{id}");
        endpoint.setQuery(Map.of("verbose", "verbose"));
        properties.getApiFabric().getEndpoints().put("lookup", endpoint);
        var capture = new CaptureExchange("{\"id\":\"O-2\",\"status\":\"created\"}", HttpStatus.OK);
        var tools = scan(properties, capture, new ProxyTools());
        var create = tools.stream().filter(tool -> tool.name().equals("create_order")).findFirst().orElseThrow();
        var arguments = new java.util.LinkedHashMap<String, Object>();
        arguments.put("tenantId", "T");
        arguments.put("tags", List.of());
        arguments.put("bizMode", "x");
        arguments.put("customerId", null);
        create.invoker().invoke(arguments);
        assertThat(mapper.readTree(capture.body)).isEqualTo(mapper.readTree("{\"customerId\":null}"));

        var lookup = tools.stream().filter(tool -> tool.name().equals("lookup")).findFirst().orElseThrow();
        lookup.invoker().invoke(Map.of("id", "1", "verbose", true));
        assertThat(capture.body).isNull();
    }

    @Test
    void convertsStringNullAndVoidLikeResponses() throws Exception {
        var stringCapture = new CaptureExchange("plain text", HttpStatus.OK);
        var stringTool = tool(scan(validProperties(), stringCapture, new ProxyTools()), "string_result");
        assertThat(stringTool.invoker().invoke(Map.of())).isEqualTo("plain text");

        var nullCapture = new CaptureExchange("null", HttpStatus.OK);
        var nullTool = tool(scan(validProperties(), nullCapture, new ProxyTools()), "nullable_result");
        assertThat(nullTool.invoker().invoke(Map.of())).isNull();

        var voidCapture = new CaptureExchange("ignored", HttpStatus.OK);
        var voidTool = tool(scan(validProperties(), voidCapture, new ProxyTools()), "void_result");
        assertThat(voidTool.invoker().invoke(Map.of())).isNull();
    }

    @Test
    void wrapsConnectorTimeoutPathAndConversionFailuresWithToolRef() {
        ExchangeFunction connectorFailure = request -> Mono.error(new IllegalStateException("connector unavailable"));
        var connectorTool = tool(scan(validProperties(), connectorFailure, new ProxyTools()), "string_result");
        assertThatThrownBy(() -> connectorTool.invoker().invoke(Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("string_result", "connector unavailable");

        var invalidJson = tool(scan(validProperties(), new CaptureExchange("not-json", HttpStatus.OK), new ProxyTools()),
                "nullable_result");
        assertThatThrownBy(() -> invalidJson.invoker().invoke(Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("nullable_result", "Unrecognized token");

        var pathTool = tool(scan(validProperties(), new CaptureExchange("{}", HttpStatus.OK), new ProxyTools()),
                "create_order");
        assertThatThrownBy(() -> pathTool.invoker().invoke(Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("create_order", "tenantId");

        var timeoutProperties = validProperties();
        timeoutProperties.setRequestTimeout(java.time.Duration.ofMillis(1));
        ExchangeFunction never = request -> Mono.never();
        var timeoutTool = tool(scan(timeoutProperties, never, new ProxyTools()), "string_result");
        assertThatThrownBy(() -> timeoutTool.invoker().invoke(Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("string_result", "Timeout");
    }

    private void assertFailure(McpFabricProperties properties, String... fragments) {
        var assertion = assertThatThrownBy(() -> scan(properties, new CaptureExchange("{}", HttpStatus.OK), new ProxyTools()))
                .isInstanceOf(IllegalStateException.class);
        for (String fragment : fragments) {
            assertion.hasMessageContaining(fragment);
        }
    }

    private List<ToolRegistration> scan(
            McpFabricProperties properties, ExchangeFunction capture, Object bean) {
        return scan(
                properties,
                capture,
                new CaptureRestOperations("[]", HttpStatus.OK),
                bean);
    }

    private List<ToolRegistration> scan(
            McpFabricProperties properties,
            ExchangeFunction capture,
            RestOperations cseClient,
            Object bean) {
        var factory = new DefaultListableBeanFactory();
        var definition = new RootBeanDefinition(bean.getClass());
        definition.setInstanceSupplier(() -> bean);
        factory.registerBeanDefinition("tools", definition);
        WebClient apiFabricClient = WebClient.builder().exchangeFunction(capture).build();
        List<RemoteToolEndpointHandler> handlers = List.of(
                new ApiFabricToolEndpointHandler(properties, mapper, apiFabricClient),
                new CseToolEndpointHandler(properties, mapper, cseClient));
        return new McpToolScanner(factory, mapper, handlers).scan();
    }

    private static ToolRegistration tool(List<ToolRegistration> tools, String name) {
        return tools.stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    private static McpFabricProperties validProperties() {
        var properties = new McpFabricProperties();
        properties.getApiFabric().setBaseUrl("https://fabric.example/base/");
        var create = new McpFabricProperties.ApiFabricEndpoint();
        create.setMethod("POST");
        create.setPathTemplate("/tenants/{tenantId}/orders");
        create.setQuery(Map.of("tag", "tags"));
        create.getHeaders().setBusiness(Map.of("X-Biz-Mode", "bizMode"));
        properties.getApiFabric().getEndpoints().put("create_order", create);
        var reserve = new McpFabricProperties.CseEndpoint();
        reserve.setMethod("POST");
        reserve.setUriTemplate("cse://inventory-service/warehouses/{warehouseId}/reservations");
        properties.getCse().getEndpoints().put("reserve_inventory", reserve);
        properties.getApiFabric().getEndpoints().put("string_result", endpoint("GET", "/string"));
        properties.getApiFabric().getEndpoints().put("nullable_result", endpoint("GET", "/nullable"));
        properties.getApiFabric().getEndpoints().put("void_result", endpoint("DELETE", "/void"));
        return properties;
    }

    private static McpFabricProperties.ApiFabricEndpoint endpoint(String method, String path) {
        var endpoint = new McpFabricProperties.ApiFabricEndpoint();
        endpoint.setMethod(method);
        endpoint.setPathTemplate(path);
        return endpoint;
    }

    record OrderResponse(String id, String status) {
    }

    static class ProxyTools {
        @Tool(
                name = "create_order",
                readOnly = true,
                destructive = false,
                idempotent = true,
                openWorld = false)
        OrderResponse create(
                String tenantId, List<String> tags, String bizMode,
                @ToolParam(required = false) String customerId,
                @ToolParam(required = false) List<Map<String, Object>> lines) {
            throw new AssertionError("远程代理方法体不应执行");
        }

        @Tool(name = "reserve_inventory")
        List<String> reserve(String warehouseId, String sku) {
            throw new AssertionError("远程代理方法体不应执行");
        }

        @Tool
        String echo(String message) {
            return message;
        }

        @Tool
        String lookup(String id, Boolean verbose) {
            throw new AssertionError();
        }

        @Tool(name = "string_result")
        String stringResult() {
            throw new AssertionError();
        }

        @Tool(name = "nullable_result")
        OrderResponse nullableResult() {
            throw new AssertionError();
        }

        @Tool(name = "void_result")
        void voidResult() {
            throw new AssertionError();
        }
    }

    static final class CaptureExchange implements ExchangeFunction {
        private final String responseBody;
        private final HttpStatus status;
        private HttpMethod method;
        private java.net.URI uri;
        private HttpHeaders headers;
        private String body;

        CaptureExchange(String responseBody, HttpStatus status) {
            this.responseBody = responseBody;
            this.status = status;
        }

        @Override
        public Mono<ClientResponse> exchange(ClientRequest request) {
            body = null;
            method = request.method();
            uri = request.url();
            headers = new HttpHeaders();
            headers.putAll(request.headers());
            var mock = new MockClientHttpRequest(request.method(), request.url());
            mock.setWriteHandler(flux -> DataBufferUtils.join(flux).doOnNext(buffer -> {
                var bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                DataBufferUtils.release(buffer);
                body = bytes.length == 0 ? null : new String(bytes, StandardCharsets.UTF_8);
            }).then());
            BodyInserter.Context context = new BodyInserter.Context() {
                @Override
                public List<org.springframework.http.codec.HttpMessageWriter<?>> messageWriters() {
                    return ExchangeStrategies.withDefaults().messageWriters();
                }

                @Override
                public java.util.Optional<org.springframework.http.server.reactive.ServerHttpRequest> serverRequest() {
                    return java.util.Optional.empty();
                }

                @Override
                public Map<String, Object> hints() {
                    return Map.of();
                }
            };
            return request.body().insert(mock, context).thenReturn(
                    ClientResponse.create(status).header("Content-Type", "application/json").body(responseBody).build());
        }
    }

    final class CaptureRestOperations extends RestTemplate {

        private final String responseBody;
        private final HttpStatus status;
        private HttpMethod method;
        private URI uri;
        private HttpHeaders headers;
        private Object body;

        CaptureRestOperations(String responseBody, HttpStatus status) {
            this.responseBody = responseBody;
            this.status = status;
        }

        /**
         * 捕获 CSE RestTemplate 请求并按声明泛型构造测试响应。
         *
         * @param url           展开后的 CSE URI
         * @param method        HTTP 方法
         * @param requestEntity 请求实体
         * @param responseType  响应泛型
         * @param <T>           响应类型
         * @return 捕获请求后构造的响应
         */
        @Override
        @SuppressWarnings("unchecked")
        public <T> ResponseEntity<T> exchange(
                URI url,
                HttpMethod method,
                HttpEntity<?> requestEntity,
                ParameterizedTypeReference<T> responseType) {
            this.uri = url;
            this.method = method;
            this.headers = new HttpHeaders();
            this.headers.putAll(requestEntity.getHeaders());
            this.body = requestEntity.getBody();
            if (!status.is2xxSuccessful()) {
                throw HttpServerErrorException.create(
                        status, status.getReasonPhrase(), HttpHeaders.EMPTY,
                        responseBody.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            }
            try {
                T result = (T) mapper.readValue(
                        responseBody, mapper.constructType(responseType.getType()));
                return ResponseEntity.status(status).body(result);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
