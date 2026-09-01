package ai.opencode.mcp.remote;

import ai.opencode.mcp.annotation.ToolParam;
import ai.opencode.mcp.api.ToolInvoker;
import ai.opencode.mcp.api.ToolRegistration;
import ai.opencode.mcp.autoconfigure.McpFabricProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriBuilder;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 组装远程工具的ToolInvoker并回填到对应的ToolRegistration
 *
 * @author beining.shang
 * @since 2026-08-31
 */
@RequiredArgsConstructor
final class RemoteToolInvokerBinder {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");
    private static final Set<String> SUPPORTED_METHODS = Set.of(
            "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE");

    private final ObjectMapper objectMapper;

    ToolRegistration bind(
            Method toolMethod,
            ToolRegistration registration,
            BindingTarget target) {
        String category = target.category;
        McpFabricProperties.Endpoint endpoint = target.endpoint;
        String uriTemplate = target.uriTemplate;
        String reference = registration.name();
        HttpMethod method = parseMethod(category, reference, endpoint.getMethod());
        validateTemplate(category, reference, uriTemplate);
        Set<String> parameters = parameterNames(toolMethod);
        Set<String> path = placeholders(uriTemplate);
        path.forEach(name -> requireParameter(category, reference, parameters, name, "Path"));
        validateMappings(category, reference, parameters, path, endpoint);

        Set<String> consumed = new LinkedHashSet<>(path);
        consumed.addAll(endpoint.getQuery().values());
        consumed.addAll(endpoint.getHeaders().getBusiness().values());
        List<String> bodyFields = parameters.stream()
                .filter(name -> !consumed.contains(name))
                .toList();
        Type returnType = toolMethod.getGenericReturnType();
        Map<String, String> businessHeaders = Map.copyOf(endpoint.getHeaders().getBusiness());
        Set<String> normalizedBusinessHeaderNames = new HashSet<>();
        for (String name : businessHeaders.keySet()) {
            normalizedBusinessHeaderNames.add(name.toLowerCase(Locale.ROOT));
        }
        Set<String> businessHeaderNames = Set.copyOf(normalizedBusinessHeaderNames);
        ParameterMapping parameterMapping = new ParameterMapping(
                Set.copyOf(path),
                Map.copyOf(endpoint.getQuery()),
                businessHeaders,
                businessHeaderNames,
                bodyFields);
        InvocationEndpoint invocationEndpoint = new InvocationEndpoint(reference, method);
        ToolInvoker invocation = new RemoteToolInvocation(
                target, invocationEndpoint, parameterMapping, returnType);
        return registration.withInvoker(invocation);
    }

    private static void validateMappings(
            String category,
            String reference,
            Set<String> parameters,
            Set<String> path,
            McpFabricProperties.Endpoint endpoint) {
        validateMap(category, reference, "Query", parameters, endpoint.getQuery());
        validateMap(category, reference, "Business header", parameters, endpoint.getHeaders().getBusiness());
        Set<String> querySources = new HashSet<>(endpoint.getQuery().values());
        for (String source : querySources) {
            if (path.contains(source)) {
                throw failure(category, reference, "Parameter " + source + " cannot be used for both Path and Query");
            }
        }
        for (Map.Entry<String, String> item : endpoint.getHeaders().getBusiness().entrySet()) {
            if (RemoteRequestHeaders.isExcluded(item.getKey())) {
                throw failure(category, reference, "Business header " + item.getKey() + " is a restricted system header");
            }
            if (path.contains(item.getValue())) {
                throw failure(category, reference,
                        "Parameter " + item.getValue() + " cannot be used for both Path and business header");
            }
            if (querySources.contains(item.getValue())) {
                throw failure(category, reference,
                        "Parameter " + item.getValue() + " cannot be used for both Query and business header");
            }
        }
    }

    private static void validateMap(
            String category,
            String reference,
            String location,
            Set<String> parameters,
            Map<String, String> mapping) {
        Set<String> downstream = new HashSet<>();
        for (Map.Entry<String, String> item : mapping.entrySet()) {
            String downstreamName = item.getKey();
            if (!StringUtils.hasText(downstreamName)
                    || !downstream.add(downstreamName.toLowerCase(Locale.ROOT))) {
                throw failure(category, reference, location + " downstream name is blank or duplicated: " + downstreamName);
            }
            requireParameter(category, reference, parameters, item.getValue(), location);
        }
    }

    private static void requireParameter(
            String category,
            String reference,
            Set<String> parameters,
            String source,
            String location) {
        if (!StringUtils.hasText(source) || !parameters.contains(source)) {
            throw failure(category, reference, location + " references unknown tool parameter: " + source);
        }
    }

    private static void validateTemplate(
            String category,
            String reference,
            String template) {
        if (!StringUtils.hasText(template)) {
            throw failure(category, reference, "URI template must not be blank");
        }
        try {
            String probe = PLACEHOLDER.matcher(template).replaceAll("value");
            URI uri = URI.create(probe);
            if (!uri.isAbsolute()) {
                throw failure(category, reference, "URI template must produce an absolute URI");
            }
        } catch (IllegalArgumentException exception) {
            throw failure(category, reference, "Invalid URI template: " + template);
        }
    }

    private static HttpMethod parseMethod(String category, String reference, String value) {
        if (!StringUtils.hasText(value)) {
            throw failure(category, reference, "Invalid method: " + value);
        }
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!SUPPORTED_METHODS.contains(normalized)) {
            throw failure(category, reference, "Invalid method: " + value);
        }
        return HttpMethod.valueOf(normalized);
    }

    private static Set<String> parameterNames(Method method) {
        Set<String> result = new LinkedHashSet<>();
        for (Parameter parameter : method.getParameters()) {
            ToolParam annotation = parameter.getAnnotation(ToolParam.class);
            if (annotation != null && StringUtils.hasText(annotation.name())) {
                result.add(annotation.name());
            } else if (parameter.isNamePresent()) {
                result.add(parameter.getName());
            } else {
                throw failure("Tool", method.getName(), "Parameter name is unavailable: " + parameter);
            }
        }
        return result;
    }

    private static Set<String> placeholders(String template) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    private static IllegalStateException failure(String category, String reference, String detail) {
        return new IllegalStateException(category + " endpoint ref=" + reference + ": " + detail);
    }

    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private final class RemoteToolInvocation implements ToolInvoker {

        private final BindingTarget target;
        private final InvocationEndpoint endpoint;
        private final ParameterMapping parameters;
        private final Type returnType;

        /**
         * 在没有请求上下文时执行远程工具调用。
         *
         * @param arguments Agent 提供的工具参数
         * @return 按注解方法返回类型转换的调用结果
         * @throws Exception 请求构建、远程调用或响应转换失败时抛出
         */
        @Override
        public Object invoke(Map<String, Object> arguments) throws Exception {
            return invoke(arguments, Map.of());
        }

        /**
         * 使用当前请求 Header 执行远程工具调用并透传允许的 Header。
         *
         * @param arguments Agent 提供的工具参数
         * @param headers   当前 tools/call 请求的不可变多值 Header
         * @return 按注解方法返回类型转换的调用结果
         * @throws Exception 请求构建、远程调用或响应转换失败时抛出
         */
        @Override
        public Object invoke(Map<String, Object> arguments, Map<String, List<String>> headers) throws Exception {
            Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
            URI uri = requestUri(safeArguments);
            ObjectNode body = body(safeArguments);
            HttpHeaders httpHeaders = requestHeaders(
                    safeArguments, headers == null ? Map.of() : headers, body != null);
            try {
                RemoteRequest request = new RemoteRequest(uri, endpoint.method, httpHeaders, body, returnType);
                return target.exchange.exchange(endpoint.reference, request);
            } catch (Exception exception) {
                throw new IllegalStateException(
                        "Remote tool " + endpoint.reference + " invocation failed: " + rootMessage(exception),
                        exception);
            }
        }

        private HttpHeaders requestHeaders(
                Map<String, Object> arguments,
                Map<String, List<String>> inboundHeaders,
                boolean hasBody) {
            HttpHeaders result = new HttpHeaders();
            inboundHeaders.forEach((name, values) -> {
                if (RemoteRequestHeaders.isExcluded(name)
                        || parameters.businessHeaderNames.contains(name.toLowerCase(Locale.ROOT))) {
                    return;
                }
                values.forEach(value -> {
                    RemoteRequestHeaders.validateValue(name, value);
                    result.add(name, value);
                });
            });
            parameters.businessHeaders.forEach((name, source) -> values(arguments.get(source)).forEach(value -> {
                RemoteRequestHeaders.validateValue(name, value);
                result.add(name, value);
            }));
            if (hasBody) {
                result.setContentType(MediaType.APPLICATION_JSON);
            }
            return result;
        }

        private URI requestUri(Map<String, Object> arguments) {
            DefaultUriBuilderFactory factory = new DefaultUriBuilderFactory();
            factory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.VALUES_ONLY);
            UriBuilder builder = factory.uriString(target.uriTemplate);
            parameters.query.forEach((name, source) -> values(arguments.get(source))
                    .forEach(value -> builder.queryParam(name, value)));
            Map<String, Object> variables = new LinkedHashMap<>();
            for (String name : parameters.path) {
                if (!arguments.containsKey(name) || arguments.get(name) == null) {
                    throw new IllegalArgumentException(
                            "Remote tool " + endpoint.reference + " is missing Path parameter: " + name);
                }
                variables.put(name, scalar(arguments.get(name)));
            }
            return builder.build(variables);
        }

        private ObjectNode body(Map<String, Object> arguments) {
            ObjectNode result = null;
            for (String name : parameters.bodyFields) {
                if (!arguments.containsKey(name)) {
                    continue;
                }
                if (result == null) {
                    result = objectMapper.createObjectNode();
                }
                result.set(name, objectMapper.valueToTree(arguments.get(name)));
            }
            return result;
        }

        private List<String> values(Object value) {
            if (value == null) {
                return List.of();
            }
            if (value instanceof Collection<?> collection) {
                return collection.stream().map(this::scalar).toList();
            }
            if (value.getClass().isArray()) {
                int size = java.lang.reflect.Array.getLength(value);
                List<String> result = new ArrayList<>(size);
                for (int index = 0; index < size; index++) {
                    result.add(scalar(java.lang.reflect.Array.get(value, index)));
                }
                return result;
            }
            return List.of(scalar(value));
        }

        private String scalar(Object value) {
            return objectMapper.convertValue(value, String.class);
        }

    }

    /** 描述单个 handler 提供的端点绑定目标。 */
    @RequiredArgsConstructor
    static final class BindingTarget {
        final String category;
        final McpFabricProperties.Endpoint endpoint;
        final String uriTemplate;
        final RemoteExchange exchange;
    }

    /** 保存远程调用的工具引用和 HTTP 方法。 */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class InvocationEndpoint {
        private final String reference;
        private final HttpMethod method;
    }

    /** 保存工具参数到下游请求位置的不可变映射。 */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private static final class ParameterMapping {
        private final Set<String> path;
        private final Map<String, String> query;
        private final Map<String, String> businessHeaders;
        private final Set<String> businessHeaderNames;
        private final List<String> bodyFields;
    }

    /** 保存一次已经组装完成的远程请求。 */
    @RequiredArgsConstructor
    static final class RemoteRequest {
        final URI uri;
        final HttpMethod method;
        final HttpHeaders headers;
        final ObjectNode body;
        final Type returnType;
    }

    /**
     * 执行已完成参数映射的单类型远程请求。
     */
    @FunctionalInterface
    interface RemoteExchange {

        Object exchange(String reference, RemoteRequest request) throws Exception;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }
}
