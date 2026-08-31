package ai.opencode.mcp.remote;

import ai.opencode.mcp.annotation.Tool;
import ai.opencode.mcp.annotation.ToolParam;
import ai.opencode.mcp.api.ToolInvoker;
import ai.opencode.mcp.api.ToolRegistration;
import ai.opencode.mcp.autoconfigure.McpFabricProperties;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriBuilder;

/**
 * 编译并执行不同远程端点类型共用的请求映射计划。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
@RequiredArgsConstructor
final class RemoteToolBindingFactory {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");
  private static final Set<String> SUPPORTED_METHODS = Set.of(
      "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE");

  private final ObjectMapper objectMapper;
  private final RemoteToolWebClientProvider clients;
  private final Duration requestTimeout;

  ToolRegistration bind(
      String category,
      Method toolMethod,
      ToolRegistration registration,
      McpFabricProperties.Endpoint endpoint,
      String uriTemplate,
      Tool.Type type) {
    String reference = registration.name();
    HttpMethod method = parseMethod(category, reference, endpoint.getMethod());
    validateTemplate(category, reference, uriTemplate, type);
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
    JavaType returnType = objectMapper.constructType(toolMethod.getGenericReturnType());
    Map<String, String> businessHeaders = Map.copyOf(endpoint.getHeaders().getBusiness());
    Set<String> normalizedBusinessHeaderNames = new HashSet<>();
    for (String name : businessHeaders.keySet()) {
      normalizedBusinessHeaderNames.add(name.toLowerCase(Locale.ROOT));
    }
    Set<String> businessHeaderNames = Set.copyOf(normalizedBusinessHeaderNames);
    ToolInvoker invocation = new RemoteToolInvocation(
        reference,
        uriTemplate,
        method,
        type,
        Set.copyOf(path),
        Map.copyOf(endpoint.getQuery()),
        businessHeaders,
        businessHeaderNames,
        bodyFields,
        returnType);
    return registration.withInvoker(invocation).withType(type);
  }

  private static void validateMappings(
      String category,
      String reference,
      Set<String> parameters,
      Set<String> path,
      McpFabricProperties.Endpoint endpoint) {
    validateMap(category, reference, "Query", parameters, endpoint.getQuery());
    validateMap(category, reference, "业务 Header", parameters, endpoint.getHeaders().getBusiness());
    Set<String> querySources = new HashSet<>(endpoint.getQuery().values());
    for (String source : querySources) {
      if (path.contains(source)) {
        throw failure(category, reference, "参数 " + source + " 同时用于 Path 和 Query");
      }
    }
    for (Map.Entry<String, String> item : endpoint.getHeaders().getBusiness().entrySet()) {
      if (RemoteRequestHeaders.isExcluded(item.getKey())) {
        throw failure(category, reference, "业务 Header " + item.getKey() + " 是系统排除名称");
      }
      if (path.contains(item.getValue())) {
        throw failure(category, reference, "参数 " + item.getValue() + " 同时用于 Path 和业务 Header");
      }
      if (querySources.contains(item.getValue())) {
        throw failure(category, reference, "参数 " + item.getValue() + " 同时用于 Query 和业务 Header");
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
        throw failure(category, reference, location + " 下游名称重复或为空: " + downstreamName);
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
      throw failure(category, reference, location + " 引用未知工具参数: " + source);
    }
  }

  private static void validateTemplate(
      String category,
      String reference,
      String template,
      Tool.Type type) {
    if (!StringUtils.hasText(template)) {
      throw failure(category, reference, "URI template 不能为空");
    }
    try {
      String probe = PLACEHOLDER.matcher(template).replaceAll("value");
      URI uri = URI.create(probe);
      if (!uri.isAbsolute()) {
        throw failure(category, reference, "URI template 必须生成绝对 URI");
      }
      if (type == Tool.Type.CSE
          && (!"cse".equalsIgnoreCase(uri.getScheme()) || !StringUtils.hasText(uri.getAuthority()))) {
        throw failure(category, reference, "CSE URI 必须使用 cse://service-name/... 格式");
      }
    } catch (IllegalArgumentException exception) {
      throw failure(category, reference, "非法 URI template: " + template);
    }
  }

  private static HttpMethod parseMethod(String category, String reference, String value) {
    if (!StringUtils.hasText(value)) {
      throw failure(category, reference, "非法 method: " + value);
    }
    String normalized = value.toUpperCase(Locale.ROOT);
    if (!SUPPORTED_METHODS.contains(normalized)) {
      throw failure(category, reference, "非法 method: " + value);
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
        throw failure("工具", method.getName(), "参数名不可用: " + parameter);
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
    return new IllegalStateException(category + " 端点 ref=" + reference + ": " + detail);
  }

  @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
  private final class RemoteToolInvocation implements ToolInvoker {

    private final String reference;
    private final String uriTemplate;
    private final HttpMethod method;
    private final Tool.Type type;
    private final Set<String> path;
    private final Map<String, String> query;
    private final Map<String, String> businessHeaders;
    private final Set<String> businessHeaderNames;
    private final List<String> bodyFields;
    private final JavaType returnType;

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
     * @param headers 当前 tools/call 请求的不可变多值 Header
     * @return 按注解方法返回类型转换的调用结果
     * @throws Exception 请求构建、远程调用或响应转换失败时抛出
     */
    @Override
    public Object invoke(Map<String, Object> arguments, Map<String, List<String>> headers) throws Exception {
      Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
      URI uri = requestUri(safeArguments);
      WebClient.RequestBodySpec request = clients.webClient(type).method(method).uri(uri);
      addHeaders(request, safeArguments, headers == null ? Map.of() : headers);
      ObjectNode body = body(safeArguments);
      if (body != null) {
        request.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
      }
      try {
        byte[] response = request.exchangeToMono(clientResponse -> clientResponse.bodyToMono(byte[].class)
            .defaultIfEmpty(new byte[0])
            .flatMap(bytes -> clientResponse.statusCode().is2xxSuccessful()
                ? reactor.core.publisher.Mono.just(bytes)
                : reactor.core.publisher.Mono.error(new IllegalStateException(
                    "远程工具 " + reference + " 返回 HTTP "
                        + clientResponse.statusCode().value() + ": "
                        + new String(bytes, StandardCharsets.UTF_8)))))
            .block(requestTimeout);
        return convert(response);
      } catch (Exception exception) {
        throw new IllegalStateException(
            "远程工具 " + reference + " 调用失败: " + rootMessage(exception), exception);
      }
    }

    private void addHeaders(
        WebClient.RequestBodySpec request,
        Map<String, Object> arguments,
        Map<String, List<String>> requestHeaders) {
      request.headers(headers -> {
        requestHeaders.forEach((name, values) -> {
          if (RemoteRequestHeaders.isExcluded(name)
              || businessHeaderNames.contains(name.toLowerCase(Locale.ROOT))) {
            return;
          }
          values.forEach(value -> {
            RemoteRequestHeaders.validateValue(name, value);
            headers.add(name, value);
          });
        });
        businessHeaders.forEach((name, source) -> values(arguments.get(source)).forEach(value -> {
          RemoteRequestHeaders.validateValue(name, value);
          headers.add(name, value);
        }));
      });
    }

    private URI requestUri(Map<String, Object> arguments) {
      DefaultUriBuilderFactory factory = new DefaultUriBuilderFactory();
      factory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.VALUES_ONLY);
      UriBuilder builder = factory.uriString(uriTemplate);
      query.forEach((name, source) -> values(arguments.get(source))
          .forEach(value -> builder.queryParam(name, value)));
      Map<String, Object> variables = new LinkedHashMap<>();
      for (String name : path) {
        if (!arguments.containsKey(name) || arguments.get(name) == null) {
          throw new IllegalArgumentException(
              "远程工具 " + reference + " 缺少 Path 参数: " + name);
        }
        variables.put(name, scalar(arguments.get(name)));
      }
      return builder.build(variables);
    }

    private ObjectNode body(Map<String, Object> arguments) {
      ObjectNode result = null;
      for (String name : bodyFields) {
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

    private Object convert(byte[] bytes) throws Exception {
      Class<?> rawType = returnType.getRawClass();
      if (rawType == void.class || rawType == Void.class) {
        return null;
      }
      if (bytes.length == 0) {
        return null;
      }
      if (rawType == String.class) {
        return new String(bytes, StandardCharsets.UTF_8);
      }
      return objectMapper.readValue(bytes, returnType);
    }
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
