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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.util.unit.DataSize;
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

    @TempDir
    Path temporaryDirectory;

    @Test
    @DisplayName("隔离 API Fabric 与 CSE 客户端依赖")
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
    @DisplayName("按自动 Path、Body、Query 和 Header 规则绑定 Fabric 请求")
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
    @DisplayName("保留 CSE scheme 并转换泛型响应且不执行代理方法体")
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
    @DisplayName("未配置 RestTemplate 时在发布 CSE 工具前失败")
    void failsBeforePublishingCseToolWhenRestTemplateIsNotConfigured() {
        assertThatThrownBy(() -> scan(
                validProperties(), new CaptureExchange("{}", HttpStatus.OK), null, new ProxyTools()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CSE RestTemplate", "cseRestOperations");
    }

    @Test
    @DisplayName("将 CSE 状态码和响应体映射为工具错误")
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
    @DisplayName("未匹配的注解工具保持本地且下游错误被映射")
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
    @DisplayName("返回任何注册项前校验完整工具目录")
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

        McpFabricProperties blankMethod = validProperties();
        blankMethod.getApiFabric().getEndpoints().get("create_order").setMethod(" ");
        assertFailure(blankMethod, "API Fabric", "create_order", "Invalid method");

        McpFabricProperties blankQuerySource = validProperties();
        blankQuerySource.getApiFabric().getEndpoints().get("create_order").getQuery().put("blank", " ");
        assertFailure(blankQuerySource, "API Fabric", "create_order", "unknown tool parameter");

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

        McpFabricProperties blankBaseUrl = validProperties();
        blankBaseUrl.getApiFabric().setBaseUrl(" ");
        assertFailure(blankBaseUrl, "API Fabric", "base-url", "blank");

        McpFabricProperties relativeBaseUrl = validProperties();
        relativeBaseUrl.getApiFabric().setBaseUrl("fabric/base");
        assertFailure(relativeBaseUrl, "API Fabric", "base-url", "absolute");

        McpFabricProperties unsupportedBaseUrl = validProperties();
        unsupportedBaseUrl.getApiFabric().setBaseUrl("ftp://fabric.example/base");
        assertFailure(unsupportedBaseUrl, "API Fabric", "base-url", "http or https");

        McpFabricProperties invalidBaseUrl = validProperties();
        invalidBaseUrl.getApiFabric().setBaseUrl("https://invalid host");
        assertFailure(invalidBaseUrl, "API Fabric", "base-url", "invalid URI");

        McpFabricProperties blankCseUri = validProperties();
        blankCseUri.getCse().getEndpoints().get("reserve_inventory").setUriTemplate(" ");
        assertFailure(blankCseUri, "CSE", "reserve_inventory", "blank");

        McpFabricProperties invalidCseUri = validProperties();
        invalidCseUri.getCse().getEndpoints().get("reserve_inventory").setUriTemplate("cse://bad host/{id}");
        assertFailure(invalidCseUri, "CSE", "reserve_inventory", "invalid URI");

        McpFabricProperties noFabricEndpoints = new McpFabricProperties();
        noFabricEndpoints.getCse().getEndpoints().put(
                "reserve_inventory", validProperties().getCse().getEndpoints().get("reserve_inventory"));
        assertThat(scan(noFabricEndpoints, new CaptureExchange("{}", HttpStatus.OK), new ProxyTools()))
                .extracting(ToolRegistration::name)
                .contains("reserve_inventory");
    }

    @Test
    @DisplayName("发布前校验 multipart 文件映射和表单字段类型")
    void validatesMultipartFileMappingsAndFormFieldTypesBeforePublishing() {
        McpFabricProperties multiple = uploadProperties("upload");
        multiple.getApiFabric().getEndpoints().get("upload").setFiles(Map.of(
                "dsl", "filePath", "second", "catalog"));
        assertFailure(multiple, new MultipartTools(), "API Fabric", "upload", "Exactly one file part");

        McpFabricProperties blank = uploadProperties("upload");
        blank.getApiFabric().getEndpoints().get("upload").setFiles(Map.of(" ", "filePath"));
        assertFailure(blank, new MultipartTools(), "API Fabric", "upload", "File part", "blank");

        McpFabricProperties unknown = uploadProperties("upload");
        unknown.getApiFabric().getEndpoints().get("upload").setFiles(Map.of("dsl", "missing"));
        assertFailure(unknown, new MultipartTools(), "API Fabric", "upload", "missing");

        McpFabricProperties wrongType = uploadProperties("upload");
        wrongType.getApiFabric().getEndpoints().get("upload").setFiles(Map.of("dsl", "overwrite"));
        assertFailure(wrongType, new MultipartTools(), "API Fabric", "upload", "overwrite", "String");

        McpFabricProperties conflict = uploadProperties("upload");
        conflict.getApiFabric().getEndpoints().get("upload").setPathTemplate("/upload/{filePath}");
        assertFailure(conflict, new MultipartTools(), "API Fabric", "upload", "filePath", "Path", "file part");

        McpFabricProperties queryConflict = uploadProperties("upload");
        queryConflict.getApiFabric().getEndpoints().get("upload").setQuery(Map.of("source", "filePath"));
        assertFailure(queryConflict, new MultipartTools(), "API Fabric", "upload", "filePath", "Query", "file part");

        McpFabricProperties headerConflict = uploadProperties("upload");
        headerConflict.getApiFabric().getEndpoints().get("upload").getHeaders()
                .setBusiness(Map.of("X-Source", "filePath"));
        assertFailure(headerConflict, new MultipartTools(),
                "API Fabric", "upload", "filePath", "business header", "file part");

        assertInvalidFormField("upload_object", "metadata");
        assertInvalidFormField("upload_map", "metadata");
        assertInvalidFormField("upload_raw", "metadata");
        assertInvalidFormField("upload_nested", "metadata");
        assertThat(scan(uploadProperties("upload_scalars"), new CaptureExchange("ok", HttpStatus.OK),
                new MultipartTools())).extracting(ToolRegistration::name).contains("upload_scalars");
        assertThat(scan(uploadProperties("upload_boolean"), new CaptureExchange("ok", HttpStatus.OK),
                new MultipartTools())).extracting(ToolRegistration::name).contains("upload_boolean");
        assertInvalidFormField("upload_objects", "metadata");

        McpFabricProperties valid = uploadProperties("upload_array");
        assertThat(scan(valid, new CaptureExchange("\"ok\"", HttpStatus.OK), new MultipartTools()))
                .extracting(ToolRegistration::name)
                .contains("upload_array");
    }

    @Test
    @DisplayName("API Fabric 发送 multipart 文件和普通请求参数")
    void sendsApiFabricMultipartFileAndRequestParameters() throws Exception {
        Path file = Files.writeString(temporaryDirectory.resolve("schema.unknown-dsl"), "create table demo");
        McpFabricProperties properties = uploadProperties("upload");
        CaptureExchange capture = new CaptureExchange("ok", HttpStatus.OK);
        ToolRegistration tool = tool(scan(properties, capture, new MultipartTools()), "upload");

        Object result = tool.invoker().invoke(Map.of(
                "filePath", file.toString(),
                "catalog", "main",
                "overwrite", true,
                "tags", List.of("one", "two"),
                "mode", UploadMode.CREATE), Map.of("catalog", List.of("inbound-header")));

        assertThat(result).isEqualTo("ok");
        assertThat(capture.method).isEqualTo(HttpMethod.POST);
        assertThat(capture.headers.getContentType()).isNotNull();
        assertThat(capture.headers.getContentType().isCompatibleWith(org.springframework.http.MediaType.MULTIPART_FORM_DATA))
                .isTrue();
        assertThat(capture.headers.getFirst("catalog")).isEqualTo("inbound-header");
        assertThat(capture.body)
                .contains("name=\"dsl\"", "filename=\"schema.unknown-dsl\"",
                        "Content-Type: application/octet-stream", "create table demo")
                .contains("name=\"catalog\"", "main")
                .contains("name=\"overwrite\"", "true")
                .contains("name=\"mode\"", "CREATE");
        assertThat(count(capture.body, "name=\"tags\"")).isEqualTo(2);

        Files.delete(file);
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    @DisplayName("CSE 发送语义一致的 multipart parts")
    void sendsCseMultipartWithEquivalentParts() throws Exception {
        Path file = Files.writeString(temporaryDirectory.resolve("schema.txt"), "create table demo");
        McpFabricProperties properties = new McpFabricProperties();
        McpFabricProperties.CseEndpoint endpoint = new McpFabricProperties.CseEndpoint();
        endpoint.setMethod("POST");
        endpoint.setUriTemplate("cse://table-service/v1/createTable");
        endpoint.setFiles(Map.of("dsl", "filePath"));
        properties.getCse().getEndpoints().put("upload", endpoint);
        CaptureRestOperations capture = new CaptureRestOperations("\"ok\"", HttpStatus.OK);
        ToolRegistration tool = tool(scan(
                properties, new CaptureExchange("{}", HttpStatus.OK), capture, new MultipartTools()), "upload");

        assertThat(tool.invoker().invoke(Map.of(
                "filePath", file.toString(),
                "catalog", "main",
                "overwrite", false,
                "tags", List.of("one", "two"),
                "mode", UploadMode.REPLACE))).isEqualTo("ok");

        assertThat(capture.uri.toString()).isEqualTo("cse://table-service/v1/createTable");
        assertThat(capture.headers.getContentType()).isEqualTo(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
        assertThat(capture.body).isInstanceOf(MultiValueMap.class);
        @SuppressWarnings("unchecked")
        MultiValueMap<String, HttpEntity<?>> parts = (MultiValueMap<String, HttpEntity<?>>) capture.body;
        HttpEntity<?> filePart = parts.getFirst("dsl");
        assertThat(filePart.getHeaders().getContentType()).isEqualTo(org.springframework.http.MediaType.TEXT_PLAIN);
        assertThat(filePart.getBody()).isInstanceOf(Resource.class);
        Resource resource = (Resource) filePart.getBody();
        assertThat(resource.getFilename()).isEqualTo("schema.txt");
        assertThat(resource.getContentAsString(StandardCharsets.UTF_8)).isEqualTo("create table demo");
        assertThat(parts.get("tags")).hasSize(2);
        assertThat(parts.getFirst("catalog").getBody()).isEqualTo("main");

        Files.delete(file);
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    @DisplayName("数组生成重复字段且缺失、null 和空值被省略")
    void repeatsArrayFieldsAndOmitsMissingNullAndEmptyMultipartValues() throws Exception {
        Path file = Files.writeString(temporaryDirectory.resolve("schema.dsl"), "dsl");
        CaptureExchange capture = new CaptureExchange("ok", HttpStatus.OK);
        ToolRegistration arrayTool = tool(scan(
                uploadProperties("upload_array"), capture, new MultipartTools()), "upload_array");
        arrayTool.invoker().invoke(Map.of(
                "filePath", file.toString(), "codes", new int[]{1, 2}, "date", LocalDate.of(2026, 9, 1)));
        assertThat(count(capture.body, "name=\"codes\"")).isEqualTo(2);
        assertThat(capture.body).contains("2026-09-01");

        ToolRegistration upload = tool(scan(uploadProperties("upload"), capture, new MultipartTools()), "upload");
        Map<String, Object> arguments = new java.util.LinkedHashMap<>();
        arguments.put("filePath", file.toString());
        arguments.put("catalog", null);
        arguments.put("tags", List.of());
        upload.invoker().invoke(arguments);
        assertThat(capture.body).doesNotContain(
                "name=\"catalog\"", "name=\"tags\"", "name=\"overwrite\"", "name=\"mode\"");

        Files.delete(file);
    }

    @Test
    @DisplayName("校验上传路径、上限、符号链接并在失败时释放资源")
    void validatesUploadPathsLimitsSymlinksAndReleasesResourcesOnFailure() throws Exception {
        McpFabricProperties properties = uploadProperties("upload");
        properties.setMaxUploadFileSize(DataSize.ofBytes(3));
        CaptureExchange capture = new CaptureExchange("failure", HttpStatus.INTERNAL_SERVER_ERROR);
        ToolRegistration tool = tool(scan(properties, capture, new MultipartTools()), "upload");

        assertUploadFailure(tool, null, "non-blank String");
        assertUploadFailure(tool, "\0", "path is invalid");
        assertUploadFailure(tool, temporaryDirectory.resolve("missing.dsl").toString(), "does not exist");
        assertUploadFailure(tool, temporaryDirectory.toString(), "not a regular file");

        Path large = Files.writeString(temporaryDirectory.resolve("large.dsl"), "1234");
        assertUploadFailure(tool, large.toString(), "exceeds limit");

        Path unreadable = Files.writeString(temporaryDirectory.resolve("unreadable.dsl"), "12");
        try {
            Set<PosixFilePermission> original = Files.getPosixFilePermissions(unreadable);
            Files.setPosixFilePermissions(unreadable, Set.of());
            if (!Files.isReadable(unreadable)) {
                assertUploadFailure(tool, unreadable.toString(), "not readable");
            }
            Files.setPosixFilePermissions(unreadable, original);
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统没有可移除的读取权限。
        }

        Path target = Files.writeString(temporaryDirectory.resolve("target.dsl"), "123");
        Path link = temporaryDirectory.resolve("link.dsl");
        Files.createSymbolicLink(link, target.getFileName());
        assertThatThrownBy(() -> invokeUpload(tool, link.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("upload", "500", "failure");
        Files.delete(link);
        Files.delete(target);
        assertThat(Files.exists(link)).isFalse();
        assertThat(Files.exists(target)).isFalse();
    }

    @Test
    @DisplayName("省略缺失可选 Body、保留显式 null 且全消费时不发送 Body")
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
    @DisplayName("转换字符串、null 和 void 类响应")
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

        CaptureExchange emptyCapture = new CaptureExchange("", HttpStatus.OK);
        ToolRegistration emptyTool = tool(scan(validProperties(), emptyCapture, new ProxyTools()), "string_result");
        assertThat(emptyTool.invoker().invoke(Map.of())).isNull();
    }

    @Test
    @DisplayName("连接器、超时、路径与转换失败包含工具引用")
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
        assertFailure(properties, new ProxyTools(), fragments);
    }

    private void assertFailure(McpFabricProperties properties, Object bean, String... fragments) {
        var assertion = assertThatThrownBy(() -> scan(properties, new CaptureExchange("{}", HttpStatus.OK), bean))
                .isInstanceOf(IllegalStateException.class);
        for (String fragment : fragments) {
            assertion.hasMessageContaining(fragment);
        }
    }

    private void assertInvalidFormField(String reference, String parameter) {
        McpFabricProperties properties = uploadProperties(reference);
        assertFailure(properties, new MultipartTools(), "API Fabric", reference, parameter, "multipart form field");
    }

    private static void assertUploadFailure(ToolRegistration tool, String filePath, String fragment) {
        assertThatThrownBy(() -> invokeUpload(tool, filePath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("upload", String.valueOf(filePath), fragment);
    }

    private static Object invokeUpload(ToolRegistration tool, String filePath) throws Exception {
        Map<String, Object> arguments = new java.util.LinkedHashMap<>();
        arguments.put("filePath", filePath);
        arguments.put("catalog", "main");
        arguments.put("overwrite", true);
        arguments.put("tags", List.of("one"));
        arguments.put("mode", UploadMode.CREATE);
        return tool.invoker().invoke(arguments);
    }

    private static int count(String source, String fragment) {
        int result = 0;
        int offset = 0;
        while ((offset = source.indexOf(fragment, offset)) >= 0) {
            result++;
            offset += fragment.length();
        }
        return result;
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

    private static McpFabricProperties uploadProperties(String reference) {
        McpFabricProperties properties = new McpFabricProperties();
        properties.getApiFabric().setBaseUrl("https://fabric.example/base");
        McpFabricProperties.ApiFabricEndpoint endpoint = endpoint("POST", "/upload");
        endpoint.setFiles(Map.of("dsl", "filePath"));
        properties.getApiFabric().getEndpoints().put(reference, endpoint);
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

    enum UploadMode {
        CREATE,
        REPLACE
    }

    record UploadMetadata(String catalog) {
    }

    static class MultipartTools {

        @Tool
        String upload(String filePath, String catalog, boolean overwrite, List<String> tags, UploadMode mode) {
            throw new AssertionError("Remote proxy method must not execute");
        }

        @Tool(name = "upload_array")
        String uploadArray(String filePath, int[] codes, LocalDate date) {
            throw new AssertionError("Remote proxy method must not execute");
        }

        @Tool(name = "upload_object")
        String uploadObject(String filePath, UploadMetadata metadata) {
            throw new AssertionError("Remote proxy method must not execute");
        }

        @Tool(name = "upload_map")
        String uploadMap(String filePath, Map<String, String> metadata) {
            throw new AssertionError("Remote proxy method must not execute");
        }

        @Tool(name = "upload_raw")
        @SuppressWarnings("rawtypes")
        String uploadRaw(String filePath, Collection metadata) {
            throw new AssertionError("Remote proxy method must not execute");
        }

        @Tool(name = "upload_nested")
        String uploadNested(String filePath, List<List<String>> metadata) {
            throw new AssertionError("Remote proxy method must not execute");
        }

        @Tool(name = "upload_objects")
        String uploadObjects(String filePath, List<UploadMetadata> metadata) {
            throw new AssertionError("Remote proxy method must not execute");
        }

        @Tool(name = "upload_scalars")
        String uploadScalars(
                String filePath,
                Integer number,
                Character character,
                java.util.UUID uuid,
                java.util.Date date) {
            throw new AssertionError("Remote proxy method must not execute");
        }

        @Tool(name = "upload_boolean")
        String uploadBoolean(String filePath, Boolean enabled) {
            throw new AssertionError("Remote proxy method must not execute");
        }
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
            return request.body().insert(mock, context)
                    .doOnSuccess(ignored -> {
                        headers = new HttpHeaders();
                        headers.putAll(request.headers());
                        headers.putAll(mock.getHeaders());
                    })
                    .thenReturn(ClientResponse.create(status)
                            .header("Content-Type", "application/json").body(responseBody).build());
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
