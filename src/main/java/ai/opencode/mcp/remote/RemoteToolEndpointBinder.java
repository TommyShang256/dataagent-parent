package ai.opencode.mcp.remote;

import ai.opencode.mcp.api.ToolInvocationContext;
import ai.opencode.mcp.api.ToolInvoker;
import ai.opencode.mcp.api.ToolOrigin;
import ai.opencode.mcp.api.ToolRegistration;
import ai.opencode.mcp.annotation.ToolParam;
import ai.opencode.mcp.autoconfigure.McpFabricProperties;
import ai.opencode.mcp.scanner.ToolEndpointBinder;
import ai.opencode.mcp.scanner.ToolMethodRegistration;
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
import java.util.regex.Pattern;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.DefaultUriBuilderFactory;

/** 在工具发布前校验并绑定配置的 API Fabric/CSE 端点。 */
@RequiredArgsConstructor
public final class RemoteToolEndpointBinder implements ToolEndpointBinder {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");

  private final McpFabricProperties properties;
  private final ObjectMapper objectMapper;
  private final RemoteToolWebClientProvider clients;

  @Override
  public List<ToolRegistration> bind(List<ToolMethodRegistration> methods) {
    validateCatalog(methods);
    var result = new ArrayList<ToolRegistration>(methods.size());
    for (var entry : methods) {
      var ref = entry.registration().name();
      var api = properties.getApiFabric().getEndpoints().get(ref);
      var cse = properties.getCse().getEndpoints().get(ref);
      if (api != null) result.add(bind(entry, "API Fabric", api, apiUri(api), ToolOrigin.apiFabric(ref)));
      else if (cse != null) result.add(bind(entry, "CSE", cse, cse.getUriTemplate(), ToolOrigin.serverComb(ref)));
      else result.add(entry.registration());
    }
    return List.copyOf(result);
  }

  private ToolRegistration bind(
      ToolMethodRegistration entry,
      String category,
      McpFabricProperties.Endpoint endpoint,
      String uriTemplate,
      ToolOrigin origin) {
    var method = parseMethod(category, entry.registration().name(), endpoint.getMethod());
    var plan = compilePlan(category, entry, endpoint, uriTemplate, method, origin);
    return entry.registration().withInvoker(plan).withOrigin(origin);
  }

  private RemotePlan compilePlan(
      String category,
      ToolMethodRegistration entry,
      McpFabricProperties.Endpoint endpoint,
      String uriTemplate,
      HttpMethod httpMethod,
      ToolOrigin origin) {
    var ref = entry.registration().name();
    validateTemplate(category, ref, uriTemplate, origin.kind() == ToolOrigin.Kind.SERVER_COMB);
    var parameters = parameterNames(entry.method());
    var path = placeholders(uriTemplate);
    path.forEach(name -> requireParameter(category, ref, parameters, name, "Path"));
    validateMappings(category, ref, parameters, path, endpoint);
    var consumed = new LinkedHashSet<>(path);
    consumed.addAll(endpoint.getQuery().values());
    consumed.addAll(endpoint.getHeaders().getBusiness().values());
    var body = parameters.stream().filter(name -> !consumed.contains(name)).toList();
    var returnType = objectMapper.constructType(entry.method().getGenericReturnType());
    return new RemotePlan(ref, uriTemplate, httpMethod, origin.kind(), path,
        Map.copyOf(endpoint.getQuery()), Map.copyOf(endpoint.getHeaders().getBusiness()), body, returnType);
  }

  private void validateCatalog(List<ToolMethodRegistration> methods) {
    var apiRefs = properties.getApiFabric().getEndpoints().keySet();
    var cseRefs = properties.getCse().getEndpoints().keySet();
    for (var ref : apiRefs) {
      if (cseRefs.contains(ref)) fail("端点", ref, "同时配置在 API Fabric 和 CSE");
    }
    var toolNames = methods.stream().map(item -> item.registration().name()).collect(java.util.stream.Collectors.toSet());
    apiRefs.forEach(ref -> { if (!toolNames.contains(ref)) fail("API Fabric", ref, "没有对应注解工具"); });
    cseRefs.forEach(ref -> { if (!toolNames.contains(ref)) fail("CSE", ref, "没有对应注解工具"); });
    if (!apiRefs.isEmpty()) {
      var base = properties.getApiFabric().getBaseUrl();
      if (!StringUtils.hasText(base)) fail("API Fabric", "base-url", "不能为空");
      try {
        var uri = URI.create(base);
        if (!uri.isAbsolute()) fail("API Fabric", "base-url", "必须是绝对 URI");
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
    var querySources = new HashSet<>(endpoint.getQuery().values());
    for (var source : querySources) {
      if (path.contains(source)) fail(category, ref, "参数 " + source + " 同时用于 Path 和 Query");
    }
    for (var item : endpoint.getHeaders().getBusiness().entrySet()) {
      if (RemoteHeaderPolicy.isExcluded(item.getKey())) {
        fail(category, ref, "业务 Header " + item.getKey() + " 是系统排除名称");
      }
      if (path.contains(item.getValue())) fail(category, ref, "参数 " + item.getValue() + " 同时用于 Path 和业务 Header");
      if (querySources.contains(item.getValue())) fail(category, ref, "参数 " + item.getValue() + " 同时用于 Query 和业务 Header");
    }
  }

  private void validateMap(
      String category, String ref, String location, Set<String> parameters, Map<String, String> mapping) {
    var downstream = new HashSet<String>();
    for (var item : mapping.entrySet()) {
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
    var base = properties.getApiFabric().getBaseUrl();
    var path = endpoint.getPathTemplate();
    if (!StringUtils.hasText(path)) return path;
    if (!StringUtils.hasText(base)) return path;
    return base.replaceAll("/+$", "") + "/" + path.replaceAll("^/+", "");
  }

  private static void validateTemplate(String category, String ref, String template, boolean cse) {
    if (!StringUtils.hasText(template)) fail(category, ref, "URI template 不能为空");
    try {
      var probe = PLACEHOLDER.matcher(template).replaceAll("value");
      var uri = URI.create(probe);
      if (!uri.isAbsolute()) fail(category, ref, "URI template 必须生成绝对 URI");
      if (cse && (!"cse".equalsIgnoreCase(uri.getScheme()) || !StringUtils.hasText(uri.getAuthority()))) {
        fail(category, ref, "CSE URI 必须使用 cse://service-name/... 格式");
      }
    } catch (IllegalArgumentException exception) {
      fail(category, ref, "非法 URI template: " + template);
    }
  }

  private static HttpMethod parseMethod(String category, String ref, String value) {
    try {
      if (!StringUtils.hasText(value)) throw new IllegalArgumentException();
      var normalized = value.toUpperCase(Locale.ROOT);
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
    var result = new LinkedHashSet<String>();
    for (Parameter parameter : method.getParameters()) {
      var annotation = parameter.getAnnotation(ToolParam.class);
      if (annotation != null && StringUtils.hasText(annotation.name())) result.add(annotation.name());
      else if (parameter.isNamePresent()) result.add(parameter.getName());
      else fail("工具", method.getName(), "参数名不可用: " + parameter);
    }
    return result;
  }

  private static Set<String> placeholders(String template) {
    var result = new LinkedHashSet<String>();
    var matcher = PLACEHOLDER.matcher(template);
    while (matcher.find()) result.add(matcher.group(1));
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

    @Override
    public Object invoke(Map<String, Object> arguments) throws Exception {
      return invoke(arguments, ToolInvocationContext.EMPTY);
    }

    @Override
    public Object invoke(Map<String, Object> arguments, ToolInvocationContext context) throws Exception {
      var safe = arguments == null ? Map.<String, Object>of() : arguments;
      var uri = requestUri(safe);
      WebClient.RequestBodySpec request = clients.webClient(originKind).method(method).uri(uri);
      request.headers(headers -> {
        var businessNames = businessHeaders.keySet().stream()
            .map(name -> name.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        context.headers().forEach((name, values) -> {
          if (RemoteHeaderPolicy.isExcluded(name) || businessNames.contains(name.toLowerCase(Locale.ROOT))) return;
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
      var body = body(safe);
      if (body != null) request.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
      try {
        var response = request.exchangeToMono(value -> value.bodyToMono(byte[].class)
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
      var factory = new DefaultUriBuilderFactory();
      factory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.VALUES_ONLY);
      var builder = factory.uriString(uriTemplate);
      query.forEach((name, source) -> values(arguments.get(source)).forEach(value -> builder.queryParam(name, value)));
      var variables = new LinkedHashMap<String, Object>();
      for (var name : path) {
        if (!arguments.containsKey(name) || arguments.get(name) == null) {
          throw new IllegalArgumentException("远程工具 " + ref + " 缺少 Path 参数: " + name);
        }
        variables.put(name, scalar(arguments.get(name)));
      }
      return builder.build(variables);
    }

    private ObjectNode body(Map<String, Object> arguments) {
      ObjectNode body = null;
      for (var name : bodyFields) {
        if (!arguments.containsKey(name)) continue;
        if (body == null) body = objectMapper.createObjectNode();
        body.set(name, objectMapper.valueToTree(arguments.get(name)));
      }
      return body;
    }

    private List<String> values(Object value) {
      if (value == null) return List.of();
      if (value instanceof Collection<?> collection) return collection.stream().map(this::scalar).toList();
      if (value.getClass().isArray()) {
        var size = java.lang.reflect.Array.getLength(value);
        var values = new ArrayList<String>(size);
        for (var index = 0; index < size; index++) values.add(scalar(java.lang.reflect.Array.get(value, index)));
        return values;
      }
      return List.of(scalar(value));
    }

    private String scalar(Object value) {
      return objectMapper.convertValue(value, String.class);
    }

    private Object convert(byte[] bytes) throws Exception {
      var raw = returnType.getRawClass();
      if (raw == void.class || raw == Void.class) return null;
      if (bytes.length == 0) return null;
      if (raw == String.class) return new String(bytes, StandardCharsets.UTF_8);
      return objectMapper.readValue(bytes, returnType);
    }
  }

  private record Response(byte[] body) {}

  private static String rootMessage(Throwable throwable) {
    var current = throwable;
    while (current.getCause() != null) current = current.getCause();
    return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
  }
}
