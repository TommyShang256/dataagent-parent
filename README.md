# DataAgent MCP

An annotation-driven, Tools-only MCP server starter for Spring Boot applications. It uses the official MCP Java SDK directly and does not depend on Spring AI.

The starter exposes one stateful Streamable HTTP endpoint with a tool catalog fixed during application startup. It supports MCP `tools/list` and `tools/call`; it does not advertise or provide Resources, Prompts, or Completions.

## Package layout

The project publishes one JAR:

- `ai.opencode.mcp.annotation`: `@Tool` and `@ToolParam`.
- `ai.opencode.mcp.api`: normalized startup tool definitions, invokers, hints, and source metadata.
- `ai.opencode.mcp.audit`: startup registration and invocation audit events.
- `ai.opencode.mcp.remote`: the single generic remote tool client contract.
- `ai.opencode.mcp.scanner`: local annotation scanning, remote discovery, and JSON Schema generation.
- `ai.opencode.mcp.registry`: immutable startup catalog registration and MCP invocation adaptation.
- `ai.opencode.mcp.autoconfigure`: Spring Boot properties and auto-configuration.

## Versions

- Java 21
- Spring Boot 3.4.13
- MCP Java SDK 2.0.1
- Jackson 2 through the Spring Boot BOM

The project depends on `mcp-core` and `mcp-json-jackson2` instead of the `mcp` convenience artifact because the latter selects Jackson 3.

## Add the starter

```xml
<dependency>
  <groupId>ai.opencode.mcp</groupId>
  <artifactId>dataagent-mcp</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Define a local tool

Any Spring bean may expose methods as tools:

```java
@Component
public class OrderTools {

  @Tool(name = "find_order", description = "Find an order", readOnly = true)
  public Order find(
      @ToolParam(description = "Order identifier") UUID orderId,
      @ToolParam(description = "Include line items", required = false) Boolean includeItems) {
    return orderService.find(orderId, Boolean.TRUE.equals(includeItems));
  }
}
```

The starter discovers annotated beans after Spring creates all singletons, generates JSON Schema 2020-12 from the method signatures, validates the complete global tool namespace, and publishes the catalog to the MCP server. The catalog cannot be changed without rebuilding or restarting the application context.

Parameter names require compiler `-parameters` metadata or an explicit `@ToolParam(name = "...")`. Applications consuming this starter must configure their own compiler accordingly.

## Register remote tools

All remote systems use `RemoteToolClient`. HTTP, authentication, discovery protocols, retries, and other integration details stay in the application-provided discovery and execution code:

```java
@Bean
RemoteToolClient orderFabric(ApiFabricHttpClient http) {
  return RemoteToolClient.of(
      "orders",
      ToolOrigin.Kind.API_FABRIC,
      http.discoverTools(),
      http::execute);
}
```

`discoverTools()` returns `Collection<RemoteToolClient.ToolDefinition>`. Definitions are copied when the client is created and become part of the same fixed startup catalog as local tools.

Tool names are globally unique across all local and remote sources. A duplicate or rejected server registration fails application initialization.

## Supported parameter schemas

The generator uses the configured Jackson mapper's deserialization model so the advertised schema and runtime argument conversion follow the same property names and generic bindings.

| Java parameter family | Schema behavior |
| --- | --- |
| primitives, boxed numbers, `BigInteger`, `BigDecimal`, atomics | integer or number |
| `String`, character values, locale, currency, file/path/charset | string |
| `UUID`, `URI`, `URL` | formatted string |
| Java time, `Date`, `Calendar`, SQL date/time | date, time, date-time, or string as appropriate |
| enum | scalar enum values produced by Jackson, including `@JsonValue` |
| `byte[]` | base64-encoded string |
| arrays, collections, sets, iterables | array with resolved item schema |
| `Map<K,V>` | object with resolved value schema when `K` is JSON-key compatible |
| `Optional<T>`, `OptionalInt`, `OptionalLong`, `OptionalDouble` | nullable/omittable value schema |
| records and beans | Jackson-visible input properties, including inheritance and configured names/access |
| nested and bounded generics | resolved recursively without erasing known bindings |
| repeated or recursive models | reusable `$defs` and `$ref` references |
| `Object`, unbounded generic values, Jackson tree nodes | intentionally open schema |

Root tool inputs and ordinary object models are closed with `additionalProperties: false`; a Jackson any-setter opens the corresponding object. Unsupported concrete types and incompatible map keys fail startup with a diagnostic containing the tool, property path, and Java type.

`required` describes whether a JSON key must be present. Explicit JSON null is distinct from an omitted key: primitives reject null, optional references may be omitted, and Java Optional variants receive their empty value when omitted or null.

General custom serializer/deserializer polymorphism, Bean Validation constraints, Swagger/OpenAPI annotations, Kotlin reflection, and output schemas are not modeled.

## Invocation results

- Strings become MCP text content directly.
- Other objects are serialized as JSON text with the configured Jackson mapper.
- A native `McpSchema.CallToolResult` is passed through.
- Ordinary invocation or serialization exceptions become MCP tool errors.

Execution is synchronous. Long-running remote tools should implement their own timeout, isolation, retry, and cancellation strategy.

## Audit logging

The default `Slf4jToolAuditLogger` writes audit records for `REGISTER` and `INVOKE`. Invocation events contain complete arguments and return values in addition to the tool name, source, origin, outcome, duration, and exception type.

No masking is applied. Applications handling credentials, personal data, or large results should replace `ToolAuditLogger` with an implementation that enforces their retention, redaction, and delivery policy. Audit delivery is observational: logger failures are reported separately and do not alter registration or invocation outcomes.

## Configuration

```yaml
opencode:
  mcp:
    enabled: true
    endpoint: /mcp
    server-name: dataagent-mcp
    server-version: 0.1.0
    request-timeout: 5m
    # keep-alive: 30s   # disabled unless configured
    max-request-size: 16MB
```

Register the endpoint as one remote MCP server:

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
mvn clean verify
```

Applications that add the starter expose `http://localhost:8080/mcp` by default.
