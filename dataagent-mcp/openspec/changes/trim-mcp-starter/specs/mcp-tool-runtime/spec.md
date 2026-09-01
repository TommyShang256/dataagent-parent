## Purpose

Defines a focused Tools-only MCP runtime that publishes a startup-fixed catalog of local and generic remote tools with accurate JSON Schema contracts for the large majority of practical Java method parameters.

## ADDED Requirements

### Requirement: Conditional MCP server activation
The starter SHALL create a stateful Streamable HTTP MCP endpoint in a servlet web application when `opencode.mcp.enabled` is true or absent, and SHALL not create the MCP infrastructure when the property is false.

#### Scenario: Default activation
- **WHEN** a servlet Spring Boot application includes the starter without setting `opencode.mcp.enabled`
- **THEN** the application exposes an MCP endpoint at `/mcp`

#### Scenario: Explicit deactivation
- **WHEN** the application sets `opencode.mcp.enabled=false`
- **THEN** the starter does not create the MCP transport, server, scanner, or registry

### Requirement: Tools-only MCP surface
The server SHALL advertise the MCP Tools capability and SHALL support tool listing and tool invocation. It SHALL NOT advertise Resources, Prompts, or Completions capabilities, and the starter SHALL NOT expose provider APIs for those features.

#### Scenario: Server capability negotiation
- **WHEN** an MCP client initializes a session
- **THEN** the returned capabilities include Tools and exclude Resources, Prompts, and Completions

#### Scenario: Tools are listed and called
- **WHEN** an initialized MCP client requests `tools/list` and then invokes a listed tool with `tools/call`
- **THEN** the server returns the startup catalog and executes the selected tool

### Requirement: Startup-fixed tool catalog
The starter SHALL discover and register all tools after Spring singleton creation and before normal application service. The published catalog SHALL remain fixed for the lifetime of the application context, and the starter SHALL NOT expose public runtime tool registration or removal operations.

#### Scenario: Startup catalog is published
- **WHEN** application initialization completes successfully
- **THEN** `tools/list` contains every valid local and remote tool discovered during initialization

#### Scenario: Runtime mutation API is absent
- **WHEN** an application consumes the starter's public API
- **THEN** no supported operation allows a tool to be added to or removed from the running MCP server

### Requirement: Local annotated tool discovery
The starter SHALL discover methods annotated as tools on Spring-managed beans and SHALL expose their declared name, title, description, input schema, and tool hints through MCP.

#### Scenario: Annotated method is registered
- **WHEN** a Spring bean contains a valid annotated tool method
- **THEN** MCP tool discovery includes one tool backed by that method

#### Scenario: Missing parameter metadata
- **WHEN** a tool parameter has neither compiler-retained parameter name metadata nor an explicit tool parameter name
- **THEN** application initialization fails with a diagnostic identifying the invalid parameter

### Requirement: Generic remote tool integration
The starter SHALL accept remote tool clients through one generic contract containing a nonblank source identifier, a nonnull origin kind, immutable startup tool definitions, and an execution function. Remote-system-specific Java client types SHALL NOT be required.

#### Scenario: Generic remote tools are discovered
- **WHEN** the application provides a valid generic remote tool client bean before initialization completes
- **THEN** every definition returned by that client is included in the startup catalog with the client's source identifier and origin kind

#### Scenario: Remote tool is invoked
- **WHEN** an MCP client invokes a registered remote tool
- **THEN** the starter passes the tool name and arguments to the supplying client's execution function

### Requirement: Consistent parameter presence and null semantics
The advertised schema and runtime conversion SHALL distinguish a missing key from an explicit JSON null. `required` SHALL describe key presence; primitive values SHALL reject null; optional reference values SHALL accept omission; and `Optional<T>`, `OptionalInt`, `OptionalLong`, and `OptionalDouble` SHALL receive their empty value when omitted or explicitly null.

#### Scenario: Required parameter is absent
- **WHEN** a tool is invoked without an input declared as required
- **THEN** the invocation returns an MCP tool error identifying the missing parameter

#### Scenario: Optional parameter is absent
- **WHEN** a tool is invoked without an optional reference or optional-wrapper parameter
- **THEN** the target method receives `null` or the corresponding empty optional value

#### Scenario: Null primitive is supplied
- **WHEN** a tool invocation explicitly supplies JSON null for a primitive parameter
- **THEN** the invocation returns an MCP tool error identifying the invalid null value

#### Scenario: Invalid optional primitive declaration
- **WHEN** a primitive parameter is declared optional
- **THEN** application initialization fails with a diagnostic identifying the unsupported declaration

### Requirement: Scalar and formatted JSON Schema coverage
The starter SHALL generate JSON Schema 2020-12 for Java string and character values, booleans, primitive and boxed integral and decimal numbers, big numbers, byte arrays, UUIDs, URIs, URLs, Java date/time values, locales, currencies, and enums. Where JSON Schema defines an interoperable format, the schema SHALL include it; enum values SHALL match values produced by the configured Jackson mapper.

#### Scenario: Scalar signature is described
- **WHEN** a tool signature contains supported scalar and formatted parameter types
- **THEN** each property has the correct JSON type, numeric kind, format, and nullability

#### Scenario: Jackson-customized enum is described
- **WHEN** an enum's configured Jackson serialization differs from its Java constant name
- **THEN** the schema enum contains the serialized JSON values accepted by runtime conversion

### Requirement: Container and generic JSON Schema coverage
The starter SHALL describe object arrays, primitive arrays, collections, sets, iterables, maps with JSON-compatible keys, optionals, Jackson tree values, nested containers, concrete generic bindings, bounded type variables, and bounded wildcards without erasing resolvable item or value types.

#### Scenario: Nested generic container is described
- **WHEN** a parameter has a type such as `Map<String, List<Optional<Order>>>`
- **THEN** the generated schema preserves the nested object, array, optional, and `Order` value structure

#### Scenario: Generic bean binding is described
- **WHEN** a tool parameter binds a generic bean such as `Page<Order>`
- **THEN** properties declared using the type variable are generated using the bound `Order` schema

#### Scenario: Unsupported map key is encountered
- **WHEN** a map key cannot be represented as a JSON object property name
- **THEN** application initialization fails with a diagnostic identifying the parameter and key type

### Requirement: Object model JSON Schema coverage
The starter SHALL describe records and Jackson-visible bean properties, including inherited properties, nested objects, configured property names, ignored properties, read/write access, and concrete nested generic bindings. Generated object schemas SHALL reject undeclared properties unless the Java/Jackson model explicitly accepts arbitrary properties.

#### Scenario: Record parameter is described
- **WHEN** a tool accepts a record containing nested and generic components
- **THEN** the schema exposes the Jackson-visible record component names and their resolved schemas

#### Scenario: Jackson property model is honored
- **WHEN** a bean uses Jackson naming, ignore, access, or any-setter behavior
- **THEN** its schema exposes the same accepted input property model as Jackson deserialization

#### Scenario: Inherited properties are described
- **WHEN** a parameter bean inherits Jackson-visible properties from a superclass or interface contract
- **THEN** the schema contains both inherited and directly declared input properties

### Requirement: Recursive schemas use references
The starter SHALL represent repeated and recursive object types with stable `$defs` entries and `$ref` references so generation terminates without discarding the recursive structure.

#### Scenario: Self-recursive object is described
- **WHEN** a parameter type directly or indirectly refers back to itself
- **THEN** the root schema terminates and the recursive location points to a reusable definition with `$ref`

#### Scenario: Repeated nested type is described once
- **WHEN** multiple parameters or properties use the same resolved Java type
- **THEN** the schema reuses one stable definition rather than duplicating incompatible copies

### Requirement: Accurate fallback and diagnostics
The starter SHALL use an unconstrained schema only for intentionally open values such as `Object`, an unbounded wildcard, or a Jackson tree node. When a declared type appears concrete but cannot be represented accurately, application initialization SHALL fail with a diagnostic identifying the tool, parameter path, and Java type.

#### Scenario: Open value is declared
- **WHEN** a parameter or nested property intentionally uses `Object` or an unbounded generic value
- **THEN** that location receives an unconstrained schema and the enclosing schema remains valid

#### Scenario: Concrete type cannot be modeled
- **WHEN** schema generation encounters a concrete input type it cannot accurately represent
- **THEN** initialization fails rather than advertising a misleading schema

### Requirement: Globally unique startup registration
The starter SHALL maintain one global tool namespace across local and remote tools. Duplicate names or any MCP server registration failure SHALL fail application initialization without publishing a partially initialized tool service.

#### Scenario: Duplicate name is discovered
- **WHEN** local or remote discovery produces the same tool name more than once
- **THEN** initialization fails with a duplicate-name diagnostic

#### Scenario: Server registration fails
- **WHEN** the MCP server rejects a discovered tool registration
- **THEN** application initialization fails and the application does not expose a partially valid catalog

### Requirement: MCP invocation result mapping
The starter SHALL pass through native MCP tool results, return strings as text content, serialize other return values as JSON text content, and convert ordinary invocation exceptions into MCP tool errors.

#### Scenario: Object result
- **WHEN** a tool returns a non-string application object
- **THEN** the MCP response contains its Jackson-serialized JSON as text content and is not marked as an error

#### Scenario: Invocation exception
- **WHEN** a tool throws an ordinary exception
- **THEN** the MCP response is marked as an error and contains a stable nonblank error message

### Requirement: Best-effort audit delivery
The starter SHALL emit startup registration and invocation audit events, including source metadata, outcome, duration, arguments, result, and error type where applicable. Failure of an audit logger SHALL NOT change a successful tool result, replace the original invocation exception, or invalidate an otherwise successful startup registration.

#### Scenario: Audit logger fails after successful invocation
- **WHEN** a tool completes successfully and the configured audit logger throws an exception
- **THEN** the caller still receives the successful tool result

#### Scenario: Audit logger fails after failed invocation
- **WHEN** a tool fails and the configured audit logger also throws an exception
- **THEN** the caller receives an MCP error based on the original tool exception

#### Scenario: Registration audit logger fails
- **WHEN** a startup tool registration succeeds and its audit logger throws an exception
- **THEN** the valid tool remains in the startup catalog

### Requirement: Replaceable infrastructure components
Applications SHALL be able to replace the JSON mapper, transport provider, MCP server, audit logger, scanner, and startup registry with their own Spring beans.

#### Scenario: Custom audit logger exists
- **WHEN** an application declares an audit logger bean
- **THEN** auto-configuration does not create the default SLF4J audit logger
