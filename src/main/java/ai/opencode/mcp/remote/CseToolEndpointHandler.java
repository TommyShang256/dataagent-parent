package ai.opencode.mcp.remote;

import ai.opencode.mcp.annotation.Tool;
import ai.opencode.mcp.api.ToolRegistration;
import ai.opencode.mcp.autoconfigure.McpFabricProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestOperations;

/**
 * 保留完整 {@code cse://} URI 并绑定 CSE 远程工具端点。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
public final class CseToolEndpointHandler implements RemoteToolEndpointHandler {

    /**
     * CSE 默认端点处理器的 Spring Bean 名称。
     */
    public static final String BEAN_NAME = "cseToolEndpointHandler";

    private final McpFabricProperties.Cse properties;
    private final RestOperations client;
    private final RemoteToolInvokerBinder bindingFactory;

    /**
     * 创建 CSE 端点处理器。
     *
     * @param properties   MCP 及 CSE 配置
     * @param objectMapper 应用的 Jackson 映射器
     * @param cseClient    CSE RestTemplate；未配置时可为空，但存在 CSE 端点时绑定将失败
     */
    public CseToolEndpointHandler(
            McpFabricProperties properties,
            ObjectMapper objectMapper,
            @Nullable RestOperations cseClient) {
        this.properties = properties.getCse();
        this.client = cseClient;
        this.bindingFactory = new RemoteToolInvokerBinder(
                objectMapper, properties.getMaxUploadFileSize().toBytes());
    }

    /**
     * 获取 CSE 端点类型名称。
     *
     * @return {@code CSE}
     */
    @Override
    public String endpointType() {
        return "CSE";
    }

    /**
     * 获取配置的 CSE 工具引用。
     *
     * @return 不可变且保持配置顺序的引用集合
     */
    @Override
    public Set<String> references() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(properties.getEndpoints().keySet()));
    }

    /**
     * 将匹配的注解方法绑定到 CSE 端点。
     *
     * @param method       注解工具对应的 Java 方法
     * @param registration 扫描得到的工具注册信息
     * @return CSE 远程工具注册信息
     */
    @Override
    public ToolRegistration bind(Method method, ToolRegistration registration) {
        String reference = registration.name();
        McpFabricProperties.CseEndpoint endpoint = properties.getEndpoints().get(reference);
        if (endpoint == null) {
            throw new IllegalArgumentException("CSE endpoint ref=" + reference + ": reference is not configured");
        }
        validateUriTemplate(reference, endpoint.getUriTemplate());
        if (client == null) {
            throw new IllegalStateException(
                    "CSE endpoint ref=" + reference
                            + ": CSE RestTemplate is not configured; provide a cseRestOperations bean");
        }
        RemoteToolInvokerBinder.BindingTarget target = new RemoteToolInvokerBinder.BindingTarget(
                endpointType(), endpoint, endpoint.getUriTemplate(), this::exchange);
        return bindingFactory.bind(method, registration, target)
                .withType(Tool.Type.CSE);
    }

    private static void validateUriTemplate(String reference, String template) {
        if (!StringUtils.hasText(template)) {
            throw new IllegalStateException("CSE endpoint ref=" + reference + ": URI template must not be blank");
        }
        try {
            URI uri = URI.create(template.replaceAll("\\{[^{}]+}", "value"));
            if (!"cse".equalsIgnoreCase(uri.getScheme()) || !StringUtils.hasText(uri.getAuthority())) {
                throw new IllegalStateException(
                        "CSE endpoint ref=" + reference + ": URI must use the cse://service-name/... format");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "CSE endpoint ref=" + reference + ": invalid URI template: " + template, exception);
        }
    }

    private Object exchange(
            String reference,
            RemoteToolInvokerBinder.RemoteRequest remoteRequest) {
        Object body = remoteRequest.payload.isMultipart()
                ? multipartBody(remoteRequest.payload)
                : remoteRequest.payload.json;
        HttpEntity<Object> requestEntity = new HttpEntity<>(body, remoteRequest.headers);
        ParameterizedTypeReference<?> responseType = ParameterizedTypeReference.forType(remoteRequest.returnType);
        try {
            ResponseEntity<?> response = client.exchange(
                    remoteRequest.uri, remoteRequest.method, requestEntity, responseType);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException(
                        "Remote tool " + reference + " returned HTTP "
                                + response.getStatusCode().value() + ": " + response.getBody());
            }
            return response.getBody();
        } catch (RestClientResponseException exception) {
            throw new IllegalStateException(
                    "Remote tool " + reference + " returned HTTP " + exception.getStatusCode().value()
                            + ": " + exception.getResponseBodyAsString(), exception);
        }
    }

    private static MultiValueMap<String, HttpEntity<?>> multipartBody(
            RemoteToolInvokerBinder.RequestPayload payload) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        RemoteToolInvokerBinder.FilePart file = payload.file;
        builder.part(file.name, new FileSystemResource(file.path))
                .filename(file.path.getFileName().toString())
                .contentType(file.mediaType);
        payload.fields.forEach((name, values) -> values.forEach(value -> builder.part(name, value)));
        return builder.build();
    }
}
