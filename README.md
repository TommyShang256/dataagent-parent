# DataAgent MCP

An annotation-driven MCP server for Spring Boot applications. It uses the official MCP Java SDK directly and does not depend on Spring AI.

## Package layout

The project publishes one JAR. Its responsibilities are separated only by Java package:

- `ai.opencode.mcp.annotation`: `@Tool` and `@ToolParam`.
- `ai.opencode.mcp.api`: normalized tool definitions and source metadata.
- `ai.opencode.mcp.audit`: tool registration, removal, and invocation audit events backed by SLF4J.
- `ai.opencode.mcp.remote`: the common remote client contract and API Fabric/CSE specializations.
- `ai.opencode.mcp.scanner`: local annotation scanning, remote discovery, and JSON Schema generation.
- `ai.opencode.mcp.autoconfigure`: Spring Boot properties and auto-configuration.
- `ai.opencode.mcp.registry`: MCP SDK registration, removal, invocation, and audit orchestration.

## Versions

- Java 21
- Spring Boot 3.4.13
- MCP Java SDK 2.0.1
- Jackson 2 through the Spring Boot BOM

The project intentionally depends on `mcp-core` and `mcp-json-jackson2` instead of the `mcp` convenience artifact, because that artifact selects Jackson 3.

## Add the starter

```xml
<dependency>
  <groupId>ai.opencode.mcp</groupId>
  <artifactId>dataagent-mcp</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Define a local BFF tool

Any Spring bean may expose methods as tools:

```java
@Component
public class OrderTools {

  @Tool(name = "find_order", description = "Find an order", readOnly = true)
  public Order find(
      @ToolParam(description = "Order identifier") String orderId,
      @ToolParam(description = "Include line items", required = false) Boolean includeItems) {
    return orderService.find(orderId, Boolean.TRUE.equals(includeItems));
  }
}
```

The starter discovers the bean after Spring creates all singletons, generates JSON Schema from the Java method signature, and registers the method in the MCP server. Tool failures are returned as MCP tool errors.

Parameter names require either compiler `-parameters` metadata or an explicit `@ToolParam(name = "...")`. The parent POM enables `-parameters` for this project.

## Register remote tools

API Fabric and CSE/ServerComb clients implement the same `RemoteToolClient` contract. They return remote definitions during discovery and execute calls through a single method. The marker interfaces supply the correct origin type:

```java
@Bean
ApiFabricClient orderFabric(ApiFabricHttpClient http) {
  return new ApiFabricClient() {
    public String id() {
      return "orders";
    }

    public Collection<RemoteToolDefinition> tools() {
      return http.discoverTools();
    }

    public Object execute(String toolName, Map<String, Object> arguments) {
      return http.execute(toolName, arguments);
    }
  };
}
```

CSE uses the same execution contract:

```java
@Bean
CseClient inventoryServices(CseHttpClient http) {
  return new CseClient() {
    public String id() {
      return "inventory";
    }

    public Collection<RemoteToolDefinition> tools() {
      return http.discoverTools();
    }

    public Object execute(String toolName, Map<String, Object> arguments) {
      return http.execute(toolName, arguments);
    }
  };
}
```

Runtime registrations are also supported through the `McpToolRegistry` bean:

```java
registry.register(registration);
registry.remove("tool_name");
```

The normalized origin types are `LOCAL`, `API_FABRIC`, `SERVER_COMB`, and `CUSTOM`. Local annotation tools and both remote client types share one scanner, registry, and MCP endpoint. HTTP details, authentication, and company-specific protocols stay inside each `RemoteToolClient` implementation.

## Audit logging

The default `Slf4jToolAuditLogger` writes structured audit records for `REGISTER`, `REMOVE`, and `INVOKE`. Invocation records contain the complete arguments and return value in addition to the tool name, source, origin, outcome, duration, and exception type. No masking is applied.

Applications may replace it by declaring a `ToolAuditLogger` bean, for example to send events to an internal audit platform.

## Configuration

```yaml
opencode:
  mcp:
    enabled: true
    endpoint: /mcp
    server-name: dataagent-mcp
    server-version: 0.1.0
    request-timeout: 5m
    keep-alive: 30s
    max-request-size: 16MB
```

The endpoint uses stateful MCP Streamable HTTP. An opencode client can register it as one remote server:

```json
{
  "mcp": {
    "servers": {
      "company": {
        "type": "remote",
        "url": "http://localhost:8080/mcp"
      }
    }
  }
}
```

## Build

```shell
mvn verify
```

Applications that add the starter expose the MCP endpoint at `http://localhost:8080/mcp` by default.
