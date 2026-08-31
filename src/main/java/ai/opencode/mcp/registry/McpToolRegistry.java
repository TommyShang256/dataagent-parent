package ai.opencode.mcp.registry;

import ai.opencode.mcp.api.ToolInvoker;
import ai.opencode.mcp.api.ToolRegistration;
import ai.opencode.mcp.audit.ToolAuditEvent;
import ai.opencode.mcp.audit.ToolAuditLogger;
import ai.opencode.mcp.remote.ServletToolContextExtractor;
import ai.opencode.mcp.scanner.McpToolScanner;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * 在 Spring 创建全部单例后构建并发布不可变的 MCP 工具目录。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class McpToolRegistry implements SmartInitializingSingleton {

  private final Supplier<List<ToolRegistration>> discovery;

  private final ObjectMapper objectMapper;

  private final ToolServer server;

  private final ToolAuditLogger auditLogger;

  private volatile Map<String, ToolRegistration> registrations = Map.of();

  /**
   * 创建连接工具扫描器、MCP Server 与审计记录器的工具注册表。
   *
   * @param scanner 工具扫描器
   * @param objectMapper 应用的 Jackson 映射器
   * @param server 同步 MCP Server
   * @param auditLogger 工具审计记录器
   */
  public McpToolRegistry(
      McpToolScanner scanner,
      ObjectMapper objectMapper,
      McpSyncServer server,
      ToolAuditLogger auditLogger) {
    this(scanner::scan, objectMapper, new ToolServer() {
      /**
       * 向 MCP Server 添加工具规格。
       *
       * @param specification 待添加的同步工具规格
       * 返回值：无。
       */
      @Override
      public void add(McpServerFeatures.SyncToolSpecification specification) {
        server.addTool(specification);
      }

      /**
       * 从 MCP Server 删除指定工具。
       *
       * @param name 待删除的工具名称
       * 返回值：无。
       */
      @Override
      public void remove(String name) {
        server.removeTool(name);
      }
    }, auditLogger);
  }

  /**
   * 在 Spring 单例创建完成后构建并一次性发布固定工具目录。
   *
   * 返回值：无。
   */
  @Override
  public void afterSingletonsInstantiated() {
    Map<String, ToolRegistration> discovered = normalize(discovery.get());
    List<ToolRegistration> added = new ArrayList<>();
    try {
      for (ToolRegistration registration : discovered.values()) {
        long started = System.nanoTime();
        server.add(toSpecification(registration));
        added.add(registration);
        audit(registration, ToolAuditEvent.Operation.REGISTER, started, null, null, null);
      }
      registrations = Collections.unmodifiableMap(discovered);
    } catch (RuntimeException exception) {
      rollback(added);
      throw exception;
    }
  }

  /**
   * 获取已发布的固定工具目录。
   *
   * @return 当前已发布的工具注册集合
   */
  public Collection<ToolRegistration> tools() {
    return registrations.values();
  }

  private Map<String, ToolRegistration> normalize(List<ToolRegistration> discovered) {
    Map<String, ToolRegistration> normalized = new LinkedHashMap<>();
    for (ToolRegistration registration : discovered) {
      ToolRegistration audited = registration.withInvoker(new ToolInvoker() {
        /**
         * 使用空请求上下文执行工具并记录审计事件。
         *
         * @param arguments 工具参数
         * @return 工具执行结果
         * @throws Exception 工具执行失败时抛出
         */
        @Override
        public Object invoke(Map<String, Object> arguments) throws Exception {
          return McpToolRegistry.this.invoke(registration, arguments, Map.of());
        }

        /**
         * 使用当前请求 Header 执行工具并记录审计事件。
         *
         * @param arguments 工具参数
         * @param headers 当前请求的不可变多值 Header
         * @return 工具执行结果
         * @throws Exception 工具执行失败时抛出
         */
        @Override
        public Object invoke(Map<String, Object> arguments, Map<String, List<String>> headers) throws Exception {
          return McpToolRegistry.this.invoke(registration, arguments, headers);
        }
      });
      ToolRegistration existing = normalized.putIfAbsent(audited.name(), audited);
      if (existing != null) {
        throw new IllegalStateException("Duplicate MCP tool name: " + audited.name());
      }
    }
    return normalized;
  }

  private void rollback(List<ToolRegistration> added) {
    for (int index = added.size() - 1; index >= 0; index--) {
      try {
        server.remove(added.get(index).name());
      } catch (RuntimeException rollbackFailure) {
        log.warn("Failed to roll back MCP tool registration tool={}", added.get(index).name(), rollbackFailure);
      }
    }
  }

  private Object invoke(
      ToolRegistration registration,
      Map<String, Object> arguments,
      Map<String, List<String>> headers) throws Exception {
    long started = System.nanoTime();
    try {
      Object result = registration.invoker().invoke(arguments, headers);
      audit(registration, ToolAuditEvent.Operation.INVOKE, started, arguments, result, null);
      return result;
    } catch (Exception exception) {
      audit(registration, ToolAuditEvent.Operation.INVOKE, started, arguments, null, exception);
      throw exception;
    }
  }

  McpServerFeatures.SyncToolSpecification toSpecification(ToolRegistration registration) {
    McpSchema.ToolAnnotations annotations = McpSchema.ToolAnnotations.builder()
        .title(registration.title())
        .readOnlyHint(registration.readOnly())
        .destructiveHint(registration.destructive())
        .idempotentHint(registration.idempotent())
        .openWorldHint(registration.openWorld())
        .build();
    McpSchema.Tool tool = McpSchema.Tool.builder(registration.name(), registration.inputSchema())
        .title(registration.title())
        .description(registration.description())
        .annotations(annotations)
        .build();
    return McpServerFeatures.SyncToolSpecification.builder()
        .tool(tool)
        .callHandler((exchange, request) -> call(
            registration, request.arguments(), exchange == null ? Map.of() : headers(exchange.transportContext())))
        .build();
  }

  McpSchema.CallToolResult call(ToolRegistration registration, Map<String, Object> arguments) {
    return call(registration, arguments, Map.of());
  }

  McpSchema.CallToolResult call(
      ToolRegistration registration,
      Map<String, Object> arguments,
      Map<String, List<String>> headers) {
    try {
      Object result = registration.invoker().invoke(arguments == null ? Map.of() : arguments, headers);
      if (result instanceof McpSchema.CallToolResult callToolResult) {
        return callToolResult;
      }
      String text = result instanceof String value ? value : objectMapper.writeValueAsString(result);
      return McpSchema.CallToolResult.builder()
          .content(List.of(McpSchema.TextContent.builder(text == null ? "null" : text).build()))
          .isError(false)
          .build();
    } catch (Exception exception) {
      return McpSchema.CallToolResult.builder()
          .content(List.of(McpSchema.TextContent.builder(errorMessage(exception)).build()))
          .isError(true)
          .build();
    }
  }

  private static Map<String, List<String>> headers(
      io.modelcontextprotocol.common.McpTransportContext transportContext) {
    if (transportContext == null) {
      return Map.of();
    }
    Object value = transportContext.get(ServletToolContextExtractor.class.getName());
    if (!(value instanceof Map<?, ?> map)) {
      return Map.of();
    }
    Map<String, List<String>> result = new LinkedHashMap<>();
    map.forEach((name, values) -> {
      if (name instanceof String headerName && values instanceof List<?> list) {
        List<String> headerValues = list.stream()
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .toList();
        result.put(headerName, headerValues);
      }
    });
    return Collections.unmodifiableMap(result);
  }

  private void audit(
      ToolRegistration registration,
      ToolAuditEvent.Operation operation,
      long started,
      Map<String, Object> arguments,
      Object result,
      Exception exception) {
    ToolAuditEvent event = new ToolAuditEvent(
        Instant.now(),
        operation,
        exception == null ? ToolAuditEvent.Outcome.SUCCESS : ToolAuditEvent.Outcome.FAILURE,
        registration.name(),
        registration.type(),
        Duration.ofNanos(System.nanoTime() - started),
        arguments,
        result,
        exception == null ? null : exception.getClass().getName());
    try {
      auditLogger.record(event);
    } catch (RuntimeException auditFailure) {
      log.warn("Failed to record MCP tool audit operation={} tool={}", operation, registration.name(), auditFailure);
    }
  }

  private static String errorMessage(Exception exception) {
    String message = exception.getMessage();
    return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
  }

  interface ToolServer {
    void add(McpServerFeatures.SyncToolSpecification specification);

    void remove(String name);
  }
}
