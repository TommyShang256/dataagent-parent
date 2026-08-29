package ai.opencode.mcp.registry;

import ai.opencode.mcp.api.ToolRegistration;
import ai.opencode.mcp.audit.ToolAuditEvent;
import ai.opencode.mcp.audit.ToolAuditLogger;
import ai.opencode.mcp.scanner.McpToolScanner;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.SmartInitializingSingleton;

/** Registers normalized tools with the MCP SDK and exposes runtime registration operations. */
public final class McpToolRegistry implements SmartInitializingSingleton {

  private final McpToolScanner scanner;

  private final ObjectMapper objectMapper;

  private final McpSyncServer server;

  private final ToolAuditLogger auditLogger;

  private final Map<String, ToolRegistration> registrations = new ConcurrentHashMap<>();

  public McpToolRegistry(
      McpToolScanner scanner,
      ObjectMapper objectMapper,
      McpSyncServer server,
      ToolAuditLogger auditLogger) {
    this.scanner = scanner;
    this.objectMapper = objectMapper;
    this.server = server;
    this.auditLogger = auditLogger;
  }

  @Override
  public void afterSingletonsInstantiated() {
    scanner.scan().forEach(this::register);
  }

  public void register(Object toolProvider) {
    scanner.scan(toolProvider).forEach(this::register);
  }

  public void register(ToolRegistration registration) {
    var started = System.nanoTime();
    var audited = registration.withInvoker(arguments -> invoke(registration, arguments));
    var existing = registrations.putIfAbsent(audited.name(), audited);
    if (existing != null) {
      var exception = new IllegalStateException("Duplicate MCP tool name: " + audited.name());
      audit(audited, ToolAuditEvent.Operation.REGISTER, started, null, null, exception);
      throw exception;
    }

    try {
      server.addTool(toSpecification(audited));
      audit(audited, ToolAuditEvent.Operation.REGISTER, started, null, null, null);
    } catch (RuntimeException exception) {
      registrations.remove(audited.name(), audited);
      audit(audited, ToolAuditEvent.Operation.REGISTER, started, null, null, exception);
      throw exception;
    }
  }

  public void remove(String name) {
    var registration = registrations.remove(name);
    if (registration == null) return;
    var started = System.nanoTime();
    try {
      server.removeTool(name);
      audit(registration, ToolAuditEvent.Operation.REMOVE, started, null, null, null);
    } catch (RuntimeException exception) {
      registrations.putIfAbsent(name, registration);
      audit(registration, ToolAuditEvent.Operation.REMOVE, started, null, null, exception);
      throw exception;
    }
  }

  public Collection<ToolRegistration> tools() {
    return Collections.unmodifiableCollection(registrations.values());
  }

  private Object invoke(ToolRegistration registration, Map<String, Object> arguments) throws Exception {
    var started = System.nanoTime();
    try {
      var result = registration.invoker().invoke(arguments);
      audit(registration, ToolAuditEvent.Operation.INVOKE, started, arguments, result, null);
      return result;
    } catch (Exception exception) {
      audit(registration, ToolAuditEvent.Operation.INVOKE, started, arguments, null, exception);
      throw exception;
    }
  }

  private McpServerFeatures.SyncToolSpecification toSpecification(ToolRegistration registration) {
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
        .callHandler((exchange, request) -> call(registration, request.arguments()))
        .build();
  }

  private McpSchema.CallToolResult call(ToolRegistration registration, Map<String, Object> arguments) {
    try {
      var result = registration.invoker().invoke(arguments == null ? Map.of() : arguments);
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

  private void audit(
      ToolRegistration registration,
      ToolAuditEvent.Operation operation,
      long started,
      Map<String, Object> arguments,
      Object result,
      Exception exception) {
    auditLogger.record(new ToolAuditEvent(
        Instant.now(),
        operation,
        exception == null ? ToolAuditEvent.Outcome.SUCCESS : ToolAuditEvent.Outcome.FAILURE,
        registration.name(),
        registration.origin(),
        Duration.ofNanos(System.nanoTime() - started),
        arguments,
        result,
        exception == null ? null : exception.getClass().getName()));
  }

  private static String errorMessage(Exception exception) {
    var message = exception.getMessage();
    return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
  }
}
