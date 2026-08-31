package ai.opencode.mcp.scanner;

import ai.opencode.mcp.annotation.Tool;
import ai.opencode.mcp.annotation.ToolParam;
import ai.opencode.mcp.api.ToolHints;
import ai.opencode.mcp.api.ToolMethodRegistration;
import ai.opencode.mcp.api.ToolOrigin;
import ai.opencode.mcp.api.ToolRegistration;
import ai.opencode.mcp.remote.RemoteToolClient;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.stream.Stream;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;

/** 发现本地注解方法和远程工具客户端，并生成标准化注册。 */
public final class McpToolScanner {

  private final ConfigurableListableBeanFactory beanFactory;

  private final ObjectMapper objectMapper;

  private final McpJsonSchemaGenerator schemaGenerator;

  private final ToolEndpointBinder endpointBinder;

  /**
   * 创建只保留本地注解方法行为的工具扫描器。
   *
   * @param beanFactory Spring Bean 工厂
   * @param objectMapper 应用的 Jackson 映射器
   */
  public McpToolScanner(ConfigurableListableBeanFactory beanFactory, ObjectMapper objectMapper) {
    this(beanFactory, objectMapper, ToolEndpointBinder.LOCAL_ONLY);
  }

  /**
   * 创建使用指定端点绑定策略的工具扫描器。
   *
   * @param beanFactory Spring Bean 工厂
   * @param objectMapper 应用的 Jackson 映射器
   * @param endpointBinder 注解工具端点绑定器
   */
  public McpToolScanner(
      ConfigurableListableBeanFactory beanFactory, ObjectMapper objectMapper, ToolEndpointBinder endpointBinder) {
    this.beanFactory = beanFactory;
    this.objectMapper = objectMapper;
    this.schemaGenerator = new McpJsonSchemaGenerator(objectMapper);
    this.endpointBinder = endpointBinder;
  }

  /**
   * 扫描本地注解工具和通用远程工具客户端，并生成固定工具目录。
   *
   * @return 标准化且已完成端点绑定的工具注册列表
   */
  public List<ToolRegistration> scan() {
    List<ToolMethodRegistration> localMethods =
        Arrays.stream(beanFactory.getBeanDefinitionNames()).flatMap(this::scanBean).toList();
    Stream<ToolRegistration> local = endpointBinder.bind(localMethods).stream();
    Stream<ToolRegistration> remote = beanFactory.getBeansOfType(RemoteToolClient.class, false, false).values().stream()
        .flatMap(this::scanRemote);
    return Stream.concat(local, remote).toList();
  }

  List<ToolRegistration> scan(Object toolProvider) {
    return endpointBinder.bind(scanMethods(toolProvider));
  }

  private Stream<ToolMethodRegistration> scanBean(String beanName) {
    Class<?> type = beanFactory.getType(beanName, false);
    if (type == null || annotatedMethods(type).isEmpty()) {
      return Stream.empty();
    }
    return scanMethods(beanFactory.getBean(beanName)).stream();
  }

  private List<ToolMethodRegistration> scanMethods(Object toolProvider) {
    return annotatedMethods(AopUtils.getTargetClass(toolProvider)).stream()
        .map(method -> new ToolMethodRegistration(method, toRegistration(toolProvider, method)))
        .toList();
  }

  private Stream<ToolRegistration> scanRemote(RemoteToolClient client) {
    ToolOrigin origin = new ToolOrigin(client.originKind(), client.id());
    return client.tools().stream().map(tool -> new ToolRegistration(
        tool.name(),
        tool.title(),
        tool.description(),
        tool.inputSchema(),
        arguments -> client.execute(tool.name(), arguments),
        tool.hints(),
        origin));
  }

  private ToolRegistration toRegistration(Object target, Method method) {
    Tool annotation = AnnotatedElementUtils.findMergedAnnotation(method, Tool.class);
    if (annotation == null) {
      throw new IllegalStateException("Missing @Tool annotation: " + method);
    }
    Method invocable = AopUtils.selectInvocableMethod(method, target.getClass());
    ReflectionUtils.makeAccessible(invocable);
    String name = annotation.name().isBlank() ? method.getName() : annotation.name();
    String title = annotation.title().isBlank() ? null : annotation.title();
    String description = annotation.description().isBlank() ? null : annotation.description();
    ToolHints hints = new ToolHints(
        annotation.readOnly(), annotation.destructive(), annotation.idempotent(), annotation.openWorld());
    return new ToolRegistration(
        name,
        title,
        description,
        schemaGenerator.forMethod(method),
        arguments -> invoke(target, invocable, method.getParameters(), arguments),
        hints,
        ToolOrigin.local(AopUtils.getTargetClass(target).getName()));
  }

  private Object invoke(Object target, Method method, Parameter[] parameters, Map<String, Object> arguments)
      throws Exception {
    Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
    Object[] values = new Object[parameters.length];
    for (int index = 0; index < parameters.length; index++) {
      Parameter parameter = parameters[index];
      ToolParam metadata = parameter.getAnnotation(ToolParam.class);
      String name = parameterName(parameter, metadata);
      boolean present = safeArguments.containsKey(name);
      Object value = safeArguments.get(name);
      if (!present) {
        if (isOptional(parameter.getType())) {
          values[index] = emptyOptional(parameter.getType());
          continue;
        }
        boolean required = metadata == null || metadata.required();
        if (required) {
          throw new IllegalArgumentException("Missing required tool parameter: " + name);
        }
        values[index] = null;
        continue;
      }
      if (value == null) {
        if (parameter.getType().isPrimitive()) {
          throw new IllegalArgumentException("Primitive tool parameter cannot be null: " + name);
        }
        if (isOptional(parameter.getType())) {
          values[index] = emptyOptional(parameter.getType());
          continue;
        }
        values[index] = null;
        continue;
      }
      values[index] = objectMapper.convertValue(value, objectMapper.constructType(parameter.getParameterizedType()));
    }

    try {
      return method.invoke(target, values);
    } catch (InvocationTargetException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof Exception checked) {
        throw checked;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw exception;
    }
  }

  private static boolean isOptional(Class<?> type) {
    return type == Optional.class || type == OptionalInt.class || type == OptionalLong.class
        || type == OptionalDouble.class;
  }

  private static Object emptyOptional(Class<?> type) {
    if (type == Optional.class) {
      return Optional.empty();
    }
    if (type == OptionalInt.class) {
      return OptionalInt.empty();
    }
    if (type == OptionalLong.class) {
      return OptionalLong.empty();
    }
    if (type == OptionalDouble.class) {
      return OptionalDouble.empty();
    }
    throw new IllegalArgumentException("Unsupported optional type: " + type.getName());
  }

  private static List<Method> annotatedMethods(Class<?> type) {
    return MethodIntrospector.selectMethods(
            type,
            (ReflectionUtils.MethodFilter)
                method -> AnnotatedElementUtils.findMergedAnnotation(method, Tool.class) != null)
        .stream()
        .toList();
  }

  private static String parameterName(Parameter parameter, ToolParam metadata) {
    if (metadata != null && !metadata.name().isBlank()) {
      return metadata.name();
    }
    if (parameter.isNamePresent()) {
      return parameter.getName();
    }
    throw new IllegalStateException(
        "Tool parameter names are unavailable. Compile with -parameters or set @ToolParam(name=...): " + parameter);
  }
}
