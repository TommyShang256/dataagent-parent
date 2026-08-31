package ai.opencode.mcp.registry;

import ai.opencode.mcp.api.ToolRegistration;
import ai.opencode.mcp.api.ToolInvocationContext;
import ai.opencode.mcp.api.ToolInvoker;
import ai.opencode.mcp.audit.ToolAuditEvent;
import ai.opencode.mcp.audit.ToolAuditLogger;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

/** Builds one immutable MCP tool catalog after Spring creates all singletons. */
public final class McpToolRegistry implements SmartInitializingSingleton {

  private static final Logger logger = LoggerFactory.getLogger(McpToolRegistry.class);

  private final Supplier<List<ToolRegistration>> discovery;

  private final ObjectMapper objectMapper;

  private final ToolServer server;

  private final ToolAuditLogger auditLogger;

  private volatile Map<String, ToolRegistration> registrations = Map.of();

  public McpToolRegistry(
      McpToolScanner scanner,
      ObjectMapper objectMapper,
      McpSyncServer server,
      ToolAuditLogger auditLogger) {
    this(scanner::scan, objectMapper, new ToolServer() {
      @Override
      public void add(McpServerFeatures.SyncToolSpecification specification) {
        server.addTool(specification);
      }

      @Override
      public void remove(String name) {
        server.removeTool(name);
      }
    }, auditLogger);
  }

  McpToolRegistry(
      Supplier<List<ToolRegistration>> discovery,
      ObjectMapper objectMapper,
      ToolServer server,
      ToolAuditLogger auditLogger) {
    this.discovery = discovery;
    this.objectMapper = objectMapper;
    this.server = server;
    this.auditLogger = auditLogger;
  }

  @Override
  public void afterSingletonsInstantiated() {
    var discovered = normalize(discovery.get());
    var added = new ArrayList<ToolRegistration>();
    try {
      for (var registration : discovered.values()) {
        var started = System.nanoTime();
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

  public Collection<ToolRegistration> tools() {
    return registrations.values();
  }

  private Map<String, ToolRegistration> normalize(List<ToolRegistration> discovered) {
    var normalized = new LinkedHashMap<String, ToolRegistration>();
    for (var registration : discovered) {
      var audited = registration.withInvoker(new ToolInvoker() {
        @Override
        public Object invoke(Map<String, Object> arguments) throws Exception {
          return McpToolRegistry.this.invoke(registration, arguments, ToolInvocationContext.EMPTY);
        }

        @Override
        public Object invoke(Map<String, Object> arguments, ToolInvocationContext context) throws Exception {
          return McpToolRegistry.this.invoke(registration, arguments, context);
        }
      });
      var existing = normalized.putIfAbsent(audited.name(), audited);
      if (existing != null) throw new IllegalStateException("Duplicate MCP tool name: " + audited.name());
    }
    return normalized;
  }

  private void rollback(List<ToolRegistration> added) {
    for (var index = added.size() - 1; index >= 0; index--) {
      try {
        server.remove(added.get(index).name());
      } catch (RuntimeException rollbackFailure) {
        logger.warn("Failed to roll back MCP tool registration tool={}", added.get(index).name(), rollbackFailure);
      }
    }
  }

  private Object invoke(
      ToolRegistration registration, Map<String, Object> arguments, ToolInvocationContext context) throws Exception {
    var started = System.nanoTime();
    try {
      var result = registration.invoker().invoke(arguments, context);
      audit(registration, ToolAuditEvent.Operation.INVOKE, started, arguments, result, null);
      return result;
    } catch (Exception exception) {
      audit(registration, ToolAuditEvent.Operation.INVOKE, started, arguments, null, exception);
      throw exception;
    }
  }

  McpServerFeatures.SyncToolSpecification toSpecification(ToolRegistration registration) {
    var hints = registration.hints();
    var annotations = McpSchema.ToolAnnotations.builder()
        .title(registration.title())
        .readOnlyHint(hints.readOnly())
        .destructiveHint(hints.destructive())
        .idempotentHint(hints.idempotent())
        .openWorldHint(hints.openWorld())
        .build();
    var tool = McpSchema.Tool.builder(registration.name(), registration.inputSchema())
        .title(registration.title())
        .description(registration.description())
        .annotations(annotations)
        .build();
    return McpServerFeatures.SyncToolSpecification.builder()
        .tool(tool)
        .callHandler((exchange, request) -> call(
            registration, request.arguments(), exchange == null ? ToolInvocationContext.EMPTY : context(exchange.transportContext())))
        .build();
  }

  McpSchema.CallToolResult call(ToolRegistration registration, Map<String, Object> arguments) {
    return call(registration, arguments, ToolInvocationContext.EMPTY);
  }

  McpSchema.CallToolResult call(
      ToolRegistration registration, Map<String, Object> arguments, ToolInvocationContext context) {
    try {
      var result = registration.invoker().invoke(arguments == null ? Map.of() : arguments, context);
      if (result instanceof McpSchema.CallToolResult callToolResult) return callToolResult;
      var text = result instanceof String value ? value : objectMapper.writeValueAsString(result);
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

  @SuppressWarnings("unchecked")
  private static ToolInvocationContext context(io.modelcontextprotocol.common.McpTransportContext transportContext) {
    if (transportContext == null) return ToolInvocationContext.EMPTY;
    var value = transportContext.get(ToolInvocationContext.TRANSPORT_HEADERS_KEY);
    if (!(value instanceof Map<?, ?> map)) return ToolInvocationContext.EMPTY;
    return new ToolInvocationContext((Map<String, List<String>>) map);
  }

  private void audit(
      ToolRegistration registration,
      ToolAuditEvent.Operation operation,
      long started,
      Map<String, Object> arguments,
      Object result,
      Exception exception) {
    var event = new ToolAuditEvent(
        Instant.now(),
        operation,
        exception == null ? ToolAuditEvent.Outcome.SUCCESS : ToolAuditEvent.Outcome.FAILURE,
        registration.name(),
        registration.origin(),
        Duration.ofNanos(System.nanoTime() - started),
        arguments,
        result,
        exception == null ? null : exception.getClass().getName());
    try {
      auditLogger.record(event);
    } catch (RuntimeException auditFailure) {
      logger.warn("Failed to record MCP tool audit operation={} tool={}", operation, registration.name(), auditFailure);
    }
  }

  private static String errorMessage(Exception exception) {
    var message = exception.getMessage();
    return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
  }

  interface ToolServer {
    void add(McpServerFeatures.SyncToolSpecification specification);

    void remove(String name);
  }
}
