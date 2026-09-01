package ai.opencode.mcp.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

/**
 * 验证 MCP 与远程端点配置属性的绑定和默认值。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
class McpFabricPropertiesTest {

    @Test
    void emptyConfigurationHasDeterministicEmptyEndpointMaps() {
        var properties = new McpFabricProperties();
        assertThat(properties.getApiFabric().getEndpoints()).isEmpty();
        assertThat(properties.getCse().getEndpoints()).isEmpty();
        assertThat(properties.getMaxUploadFileSize()).isEqualTo(DataSize.ofMegabytes(100));
    }

    @Test
    void bindsDocumentedFabricAndCseShape() {
        var source = new MapConfigurationPropertySource(Map.ofEntries(
                Map.entry("opencode.mcp.api-fabric.base-url", "https://fabric.example/api"),
                Map.entry("opencode.mcp.api-fabric.endpoints.create-order.method", "POST"),
                Map.entry("opencode.mcp.api-fabric.endpoints.create-order.path-template", "/orders/{orderId}"),
                Map.entry("opencode.mcp.api-fabric.endpoints.create-order.query.dry_run", "dryRun"),
                Map.entry("opencode.mcp.api-fabric.endpoints.create-order.files.dsl", "filePath"),
                Map.entry("opencode.mcp.api-fabric.endpoints.create-order.headers.business.X-Biz-Mode", "bizMode"),
                Map.entry("opencode.mcp.max-upload-file-size", "8MB"),
                Map.entry("opencode.mcp.cse.endpoints.reserve.method", "PUT"),
                Map.entry("opencode.mcp.cse.endpoints.reserve.uri-template", "cse://inventory/items/{sku}")));
        var properties = new Binder(source).bind("opencode.mcp", Bindable.of(McpFabricProperties.class)).get();

        assertThat(properties.getApiFabric().getBaseUrl()).isEqualTo("https://fabric.example/api");
        var fabric = properties.getApiFabric().getEndpoints().get("create-order");
        assertThat(fabric.getMethod()).isEqualTo("POST");
        assertThat(fabric.getPathTemplate()).isEqualTo("/orders/{orderId}");
        assertThat(fabric.getQuery()).containsEntry("dry_run", "dryRun");
        assertThat(fabric.getFiles()).containsEntry("dsl", "filePath");
        assertThat(fabric.getHeaders().getBusiness()).containsEntry("X-Biz-Mode", "bizMode");
        assertThat(properties.getMaxUploadFileSize()).isEqualTo(DataSize.ofMegabytes(8));
        assertThat(properties.getCse().getEndpoints().get("reserve").getUriTemplate())
                .isEqualTo("cse://inventory/items/{sku}");
    }

    @Test
    void endpointModelDoesNotExposeRemovedOverdesignedLocations() {
        assertThat(java.util.Arrays.stream(McpFabricProperties.Endpoint.class.getMethods())
                .map(java.lang.reflect.Method::getName))
                .doesNotContain("getPath", "getBody", "getPassthrough", "getFixed");
    }

    @Test
    void normalizesAndCopiesFileMappingsAndRejectsInvalidSize() {
        McpFabricProperties.ApiFabricEndpoint endpoint = new McpFabricProperties.ApiFabricEndpoint();
        Map<String, String> files = new LinkedHashMap<>();
        files.put("dsl", "filePath");
        endpoint.setFiles(files);
        files.put("late", "latePath");

        assertThat(endpoint.getFiles()).containsOnly(Map.entry("dsl", "filePath"));
        endpoint.setFiles(null);
        assertThat(endpoint.getFiles()).isEmpty();

        McpFabricProperties properties = new McpFabricProperties();
        assertThatThrownBy(() -> properties.setMaxUploadFileSize(DataSize.ofBytes(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-upload-file-size");
        assertThatThrownBy(() -> properties.setMaxUploadFileSize(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-upload-file-size");
    }
}
