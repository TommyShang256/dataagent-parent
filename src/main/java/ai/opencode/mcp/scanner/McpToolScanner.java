package ai.opencode.mcp.scanner;

import ai.opencode.mcp.annotation.Tool;
import ai.opencode.mcp.annotation.ToolParam;
import ai.opencode.mcp.api.ToolHints;
import ai.opencode.mcp.api.ToolRegistration;
import ai.opencode.mcp.remote.RemoteToolEndpointHandler;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * 发现本地注解方法和远程工具客户端，并生成经过端点绑定的标准化注册。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
public final class McpToolScanner {

  private final ConfigurableListableBeanFactory beanFactory;

  private final ObjectMapper objectMapper;

  private final McpJsonSchemaGenerator schemaGenerator;

  private final List<RemoteToolEndpointHandler> endpointHandlers;

  /**
   * 创建使用指定远程端点处理器的工具扫描器。
   *
   * @param beanFactory Spring Bean 工厂
   * @param objectMapper 应用的 Jackson 映射器
   * @param endpointHandlers 远程端点处理器
   */
  public McpToolScanner(
      ConfigurableListableBeanFactory beanFactory,
      ObjectMapper objectMapper,
      List<RemoteToolEndpointHandler> endpointHandlers) {
    this.beanFactory = beanFactory;
    this.objectMapper = objectMapper;
    this.schemaGenerator = new McpJsonSchemaGenerator(objectMapper);
    this.endpointHandlers = endpointHandlers == null ? List.of() : List.copyOf(endpointHandlers);
  }

  /**
   * 扫描本地注解工具，并生成完成远程端点绑定的固定工具目录。
   *
   * @return 标准化且已完成端点绑定的工具注册列表
   */
  public List<ToolRegistration> scan() {
    Map<String, RemoteToolEndpointHandler> handlers = handlersByReference();
    List<ToolRegistration> tools = Arrays.stream(beanFactory.getBeanDefinitionNames())
        .flatMap(beanName -> scanBean(beanName, handlers))
        .toList();
    validateReferences(tools, handlers);
    return tools;
  }

  List<ToolRegistration> scan(Object toolProvider) {
    Map<String, RemoteToolEndpointHandler> handlers = handlersByReference();
    List<ToolRegistration> tools = scanMethods(toolProvider, handlers);
    validateReferences(tools, handlers);
    return tools;
  }

  private Stream<ToolRegistration> scanBean(
      String beanName, Map<String, RemoteToolEndpointHandler> handlers) {
    Class<?> type = beanFactory.getType(beanName, false);
    if (type == null || annotatedMethods(type).isEmpty()) {
      return Stream.empty();
    }
    return scanMethods(beanFactory.getBean(beanName), handlers).stream();
  }

  private List<ToolRegistration> scanMethods(
      Object toolProvider, Map<String, RemoteToolEndpointHandler> handlers) {
    return annotatedMethods(AopUtils.getTargetClass(toolProvider)).stream()
        .map(method -> {
          ToolRegistration registration = toRegistration(toolProvider, method);
          RemoteToolEndpointHandler handler = handlers.get(registration.name());
          return handler == null ? registration : handler.bind(method, registration);
        })
        .toList();
  }

  private Map<String, RemoteToolEndpointHandler> handlersByReference() {
    Map<String, RemoteToolEndpointHandler> result = new LinkedHashMap<>();
    for (RemoteToolEndpointHandler handler : endpointHandlers) {
      if (handler == null) {
        throw new IllegalStateException("远程端点处理器不能为空");
      }
      String endpointType = handler.endpointType();
      if (!StringUtils.hasText(endpointType)) {
        throw new IllegalStateException("远程端点处理器类型名称不能为空: " + handler.getClass().getName());
      }
      Set<String> references = handler.references();
      if (references == null) {
        throw new IllegalStateException(endpointType + " 端点处理器返回了 null 引用集合");
      }
      for (String reference : references) {
        if (!StringUtils.hasText(reference)) {
          throw new IllegalStateException(endpointType + " 端点 ref 不能为空");
        }
        RemoteToolEndpointHandler previous = result.putIfAbsent(reference, handler);
        if (previous != null) {
          throw new IllegalStateException("远程端点 ref=" + reference + " 同时由 "
              + previous.endpointType() + " 和 " + endpointType + " 处理");
        }
      }
    }
    return result;
  }

  private static void validateReferences(
      List<ToolRegistration> registrations,
      Map<String, RemoteToolEndpointHandler> handlers) {
    Set<String> toolNames = new LinkedHashSet<>();
    for (ToolRegistration registration : registrations) {
      toolNames.add(registration.name());
    }
    handlers.forEach((reference, handler) -> {
      if (!toolNames.contains(reference)) {
        throw new IllegalStateException(
            handler.endpointType() + " 端点 ref=" + reference + ": 没有对应注解工具");
      }
    });
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
        Tool.Type.LOCAL);
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
