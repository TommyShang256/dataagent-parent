package ai.opencode.mcp.scanner;

import ai.opencode.mcp.annotation.ToolParam;
import ai.opencode.mcp.api.ToolInvocationContext;
import ai.opencode.mcp.api.ToolInvoker;
import ai.opencode.mcp.api.ToolMethodRegistration;
import ai.opencode.mcp.api.ToolOrigin;
import ai.opencode.mcp.api.ToolRegistration;
import ai.opencode.mcp.autoconfigure.McpFabricProperties;
import ai.opencode.mcp.remote.RemoteHeaderPolicy;
import ai.opencode.mcp.remote.RemoteToolWebClientProvider;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriBuilder;

/** 在工具发布前校验并绑定配置的 API Fabric/CSE 端点。 */
public final class RemoteToolEndpointBinder implements ToolEndpointBinder {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");

  private final McpFabricProperties properties;
  private final ObjectMapper objectMapper;
  private final RemoteToolWebClientProvider clients;

  /**
   * 创建远程工具端点绑定器。
   *
   * @param properties MCP 及远程端点配置
   * @param objectMapper 应用的 Jackson 映射器
   * @param clients 远程 WebClient 提供器
   */
  public RemoteToolEndpointBinder(
      McpFabricProperties properties, ObjectMapper objectMapper, RemoteToolWebClientProvider clients) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.clients = clients;
  }

  /**
   * 校验完整端点目录，并将匹配的注解工具绑定到远程调用计划。
   *
   * @param methods 扫描得到的注解方法注册信息
   * @return 绑定完成且可发布的工具注册列表
   */
  @Override
  public List<ToolRegistration> bind(List<ToolMethodRegistration> methods) {
    validateCatalog(methods);
    List<ToolRegistration> result = new ArrayList<>(methods.size());
    for (ToolMethodRegistration entry : methods) {
      String ref = entry.registration().name();
      McpFabricProperties.ApiFabricEndpoint api = properties.getApiFabric().getEndpoints().get(ref);
      McpFabricProperties.CseEndpoint cse = properties.getCse().getEndpoints().get(ref);
      if (api != null) {
        result.add(bind(entry, "API Fabric", api, apiUri(api), ToolOrigin.apiFabric(ref)));
      } else if (cse != null) {
        result.add(bind(entry, "CSE", cse, cse.getUriTemplate(), ToolOrigin.serverComb(ref)));
      } else {
        result.add(entry.registration());
      }
    }
    return List.copyOf(result);
  }

  private ToolRegistration bind(
      ToolMethodRegistration entry,
      String category,
      McpFabricProperties.Endpoint endpoint,
      String uriTemplate,
      ToolOrigin origin) {
    HttpMethod method = parseMethod(category, entry.registration().name(), endpoint.getMethod());
    RemotePlan plan = compilePlan(category, entry, endpoint, uriTemplate, method, origin);
    return entry.registration().withInvoker(plan).withOrigin(origin);
  }

  private RemotePlan compilePlan(
      String category,
      ToolMethodRegistration entry,
      McpFabricProperties.Endpoint endpoint,
      String uriTemplate,
      HttpMethod httpMethod,
      ToolOrigin origin) {
    String ref = entry.registration().name();
    validateTemplate(category, ref, uriTemplate, origin.kind() == ToolOrigin.Kind.SERVER_COMB);
    Set<String> parameters = parameterNames(entry.method());
    Set<String> path = placeholders(uriTemplate);
    path.forEach(name -> requireParameter(category, ref, parameters, name, "Path"));
    validateMappings(category, ref, parameters, path, endpoint);
    Set<String> consumed = new LinkedHashSet<>(path);
    consumed.addAll(endpoint.getQuery().values());
    consumed.addAll(endpoint.getHeaders().getBusiness().values());
    List<String> body = parameters.stream().filter(name -> !consumed.contains(name)).toList();
    JavaType returnType = objectMapper.constructType(entry.method().getGenericReturnType());
    return new RemotePlan(ref, uriTemplate, httpMethod, origin.kind(), path,
        Map.copyOf(endpoint.getQuery()), Map.copyOf(endpoint.getHeaders().getBusiness()), body, returnType);
  }

  private void validateCatalog(List<ToolMethodRegistration> methods) {
    Set<String> apiRefs = properties.getApiFabric().getEndpoints().keySet();
    Set<String> cseRefs = properties.getCse().getEndpoints().keySet();
    for (String ref : apiRefs) {
      if (cseRefs.contains(ref)) {
        fail("端点", ref, "同时配置在 API Fabric 和 CSE");
      }
    }
    Set<String> toolNames = methods.stream().map(item -> item.registration().name())
        .collect(java.util.stream.Collectors.toSet());
    apiRefs.forEach(ref -> {
      if (!toolNames.contains(ref)) {
        fail("API Fabric", ref, "没有对应注解工具");
      }
    });
    cseRefs.forEach(ref -> {
      if (!toolNames.contains(ref)) {
        fail("CSE", ref, "没有对应注解工具");
      }
    });
    if (!apiRefs.isEmpty()) {
      String base = properties.getApiFabric().getBaseUrl();
      if (!StringUtils.hasText(base)) {
        fail("API Fabric", "base-url", "不能为空");
      }
      try {
        URI uri = URI.create(base);
        if (!uri.isAbsolute()) {
          fail("API Fabric", "base-url", "必须是绝对 URI");
        }
        if (!Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
          fail("API Fabric", "base-url", "必须使用 http 或 https scheme");
        }
      } catch (IllegalArgumentException exception) {
        fail("API Fabric", "base-url", "非法 URI");
      }
    }
  }

  private void validateMappings(
      String category,
      String ref,
      Set<String> parameters,
      Set<String> path,
      McpFabricProperties.Endpoint endpoint) {
    validateMap(category, ref, "Query", parameters, endpoint.getQuery());
    validateMap(category, ref, "业务 Header", parameters, endpoint.getHeaders().getBusiness());
    Set<String> querySources = new HashSet<>(endpoint.getQuery().values());
    for (String source : querySources) {
      if (path.contains(source)) {
        fail(category, ref, "参数 " + source + " 同时用于 Path 和 Query");
      }
    }
    for (Map.Entry<String, String> item : endpoint.getHeaders().getBusiness().entrySet()) {
      if (RemoteHeaderPolicy.isExcluded(item.getKey())) {
        fail(category, ref, "业务 Header " + item.getKey() + " 是系统排除名称");
      }
      if (path.contains(item.getValue())) {
        fail(category, ref, "参数 " + item.getValue() + " 同时用于 Path 和业务 Header");
      }
      if (querySources.contains(item.getValue())) {
        fail(category, ref, "参数 " + item.getValue() + " 同时用于 Query 和业务 Header");
      }
    }
  }

  private void validateMap(
      String category, String ref, String location, Set<String> parameters, Map<String, String> mapping) {
    Set<String> downstream = new HashSet<>();
    for (Map.Entry<String, String> item : mapping.entrySet()) {
      if (!StringUtils.hasText(item.getKey()) || !downstream.add(item.getKey().toLowerCase(Locale.ROOT))) {
        fail(category, ref, location + " 下游名称重复或为空: " + item.getKey());
      }
      requireParameter(category, ref, parameters, item.getValue(), location);
    }
  }

  private static void requireParameter(
      String category, String ref, Set<String> parameters, String source, String location) {
    if (!StringUtils.hasText(source) || !parameters.contains(source)) {
      fail(category, ref, location + " 引用未知工具参数: " + source);
    }
  }

  private String apiUri(McpFabricProperties.ApiFabricEndpoint endpoint) {
    String base = properties.getApiFabric().getBaseUrl();
    String path = endpoint.getPathTemplate();
    if (!StringUtils.hasText(path)) {
      return path;
    }
    if (!StringUtils.hasText(base)) {
      return path;
    }
    return base.replaceAll("/+$", "") + "/" + path.replaceAll("^/+", "");
  }

  private static void validateTemplate(String category, String ref, String template, boolean cse) {
    if (!StringUtils.hasText(template)) {
      fail(category, ref, "URI template 不能为空");
    }
    try {
      String probe = PLACEHOLDER.matcher(template).replaceAll("value");
      URI uri = URI.create(probe);
      if (!uri.isAbsolute()) {
        fail(category, ref, "URI template 必须生成绝对 URI");
      }
      if (cse && (!"cse".equalsIgnoreCase(uri.getScheme()) || !StringUtils.hasText(uri.getAuthority()))) {
        fail(category, ref, "CSE URI 必须使用 cse://service-name/... 格式");
      }
    } catch (IllegalArgumentException exception) {
      fail(category, ref, "非法 URI template: " + template);
    }
  }

  private static HttpMethod parseMethod(String category, String ref, String value) {
    try {
      if (!StringUtils.hasText(value)) {
        throw new IllegalArgumentException();
      }
      String normalized = value.toUpperCase(Locale.ROOT);
      if (!Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE").contains(normalized)) {
        throw new IllegalArgumentException();
      }
      return HttpMethod.valueOf(normalized);
    } catch (IllegalArgumentException exception) {
      fail(category, ref, "非法 method: " + value);
      throw exception;
    }
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
        fail("工具", method.getName(), "参数名不可用: " + parameter);
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

  private static void fail(String category, String ref, String detail) {
    throw new IllegalStateException(category + " 端点 ref=" + ref + ": " + detail);
  }

  private final class RemotePlan implements ToolInvoker {
    private final String ref;
    private final String uriTemplate;
    private final HttpMethod method;
    private final ToolOrigin.Kind originKind;
    private final Set<String> path;
    private final Map<String, String> query;
    private final Map<String, String> businessHeaders;
    private final List<String> bodyFields;
    private final JavaType returnType;

    private RemotePlan(
        String ref, String uriTemplate, HttpMethod method, ToolOrigin.Kind originKind, Set<String> path,
        Map<String, String> query, Map<String, String> businessHeaders, List<String> bodyFields, JavaType returnType) {
      this.ref = ref;
      this.uriTemplate = uriTemplate;
      this.method = method;
      this.originKind = originKind;
      this.path = Set.copyOf(path);
      this.query = query;
      this.businessHeaders = businessHeaders;
      this.bodyFields = List.copyOf(bodyFields);
      this.returnType = returnType;
    }

    /**
     * 在没有请求上下文时执行远程工具调用。
     *
     * @param arguments Agent 提供的工具参数
     * @return 按注解方法返回类型转换的调用结果
     * @throws Exception 请求构建、远程调用或响应转换失败时抛出
     */
    @Override
    public Object invoke(Map<String, Object> arguments) throws Exception {
      return invoke(arguments, ToolInvocationContext.EMPTY);
    }

    /**
     * 使用当前请求上下文执行远程工具调用并透传允许的 Header。
     *
     * @param arguments Agent 提供的工具参数
     * @param context 当前 tools/call 请求上下文
     * @return 按注解方法返回类型转换的调用结果
     * @throws Exception 请求构建、远程调用或响应转换失败时抛出
     */
    @Override
    public Object invoke(Map<String, Object> arguments, ToolInvocationContext context) throws Exception {
      Map<String, Object> safe = arguments == null ? Map.of() : arguments;
      URI uri = requestUri(safe);
      WebClient.RequestBodySpec request = clients.webClient(originKind).method(method).uri(uri);
      request.headers(headers -> {
        Set<String> businessNames = businessHeaders.keySet().stream()
            .map(name -> name.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        context.headers().forEach((name, values) -> {
          if (RemoteHeaderPolicy.isExcluded(name) || businessNames.contains(name.toLowerCase(Locale.ROOT))) {
            return;
          }
          values.forEach(value -> {
            RemoteHeaderPolicy.validateValue(name, value);
            headers.add(name, value);
          });
        });
        businessHeaders.forEach((name, source) -> values(safe.get(source)).forEach(value -> {
          RemoteHeaderPolicy.validateValue(name, value);
          headers.add(name, value);
        }));
      });
      ObjectNode body = body(safe);
      if (body != null) {
        request.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
      }
      try {
        Response response = request.exchangeToMono(value -> value.bodyToMono(byte[].class)
            .defaultIfEmpty(new byte[0])
            .flatMap(bytes -> value.statusCode().is2xxSuccessful()
                ? reactor.core.publisher.Mono.just(new Response(bytes))
                : reactor.core.publisher.Mono.error(new IllegalStateException(
                    "远程工具 " + ref + " 返回 HTTP " + value.statusCode().value() + ": "
                        + new String(bytes, StandardCharsets.UTF_8)))))
            .block(properties.getRequestTimeout());
        return convert(response == null ? new byte[0] : response.body());
      } catch (Exception exception) {
        throw new IllegalStateException("远程工具 " + ref + " 调用失败: " + rootMessage(exception), exception);
      }
    }

    private URI requestUri(Map<String, Object> arguments) {
      DefaultUriBuilderFactory factory = new DefaultUriBuilderFactory();
      factory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.VALUES_ONLY);
      UriBuilder builder = factory.uriString(uriTemplate);
      query.forEach((name, source) -> values(arguments.get(source)).forEach(value -> builder.queryParam(name, value)));
      Map<String, Object> variables = new LinkedHashMap<>();
      for (String name : path) {
        if (!arguments.containsKey(name) || arguments.get(name) == null) {
          throw new IllegalArgumentException("远程工具 " + ref + " 缺少 Path 参数: " + name);
        }
        variables.put(name, scalar(arguments.get(name)));
      }
      return builder.build(variables);
    }

    private ObjectNode body(Map<String, Object> arguments) {
      ObjectNode body = null;
      for (String name : bodyFields) {
        if (!arguments.containsKey(name)) {
          continue;
        }
        if (body == null) {
          body = objectMapper.createObjectNode();
        }
        body.set(name, objectMapper.valueToTree(arguments.get(name)));
      }
      return body;
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
        List<String> values = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
          values.add(scalar(java.lang.reflect.Array.get(value, index)));
        }
        return values;
      }
      return List.of(scalar(value));
    }

    private String scalar(Object value) {
      return objectMapper.convertValue(value, String.class);
    }

    private Object convert(byte[] bytes) throws Exception {
      Class<?> raw = returnType.getRawClass();
      if (raw == void.class || raw == Void.class) {
        return null;
      }
      if (bytes.length == 0) {
        return null;
      }
      if (raw == String.class) {
        return new String(bytes, StandardCharsets.UTF_8);
      }
      return objectMapper.readValue(bytes, returnType);
    }
  }

  private record Response(byte[] body) {}

  private static String rootMessage(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
  }
}
