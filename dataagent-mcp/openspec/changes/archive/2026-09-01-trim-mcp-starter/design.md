## Context

See `proposal.md` for motivation. The current starter already declares only the MCP Tools capability, but its public registry permits runtime add/remove operations and its remote package contains source-specific wrappers. Its schema generator recursively maps a small set of raw Java classes, loses some generic and Jackson semantics, and collapses recursive or unknown types to `{}`.

The project is a single Java 21/Spring Boot 3.4 JAR using Jackson 2 and the synchronous MCP Java SDK. It must remain consumable as auto-configuration, introduce no new runtime dependency, and keep the tool schema in the map form accepted by the SDK.

## Goals / Non-Goals

**Goals:**

- Expose a stable Tools-only MCP surface with a catalog fixed at startup.
- Establish one small integration path for local and remote tools.
- Remove runtime mutation state and its concurrency/notification obligations.
- Generate accurate schemas for mainstream Java scalar, container, generic, record, bean, inherited, Jackson-customized, and recursive parameter models.
- Keep advertised schemas and Jackson runtime conversion aligned.
- Make unsupported concrete parameter models fail clearly during startup.

**Non-Goals:**

- Add MCP Resources, Prompts, Completions, provider APIs, or tool-list change notifications.
- Implement authentication, authorization, masking, or a new audit storage policy.
- Add live remote discovery, HTTP clients, retries, circuit breakers, async/reactive execution, or tool cancellation.
- Add MCP structured output or output schemas.
- Interpret Bean Validation, Swagger/OpenAPI, Kotlin reflection, or arbitrary third-party schema annotations.
- Fully model every possible Jackson polymorphism/custom serializer; unsupported concrete cases fail with a diagnostic.

## Decisions

### 1. Advertise Tools and no other content capability

Auto-configuration builds server capabilities with Tools enabled and leaves Resources and Prompts unset. No resource/prompt interfaces, registries, configuration, or placeholder packages are added. Protocol tests inspect initialization capabilities in addition to exercising `tools/list` and `tools/call`.

Keeping empty resource/prompt extension points was rejected because it expands the apparent support contract without delivering behavior.

### 2. Build one immutable catalog during singleton initialization

The scanner collects local annotated methods and all generic remote client definitions once. Startup registration validates the complete list for duplicate names before adding specifications to the MCP server. The registry exposes no public `register(Object)`, `register(ToolRegistration)`, or `remove(String)` mutation operations, and removal audit events are deleted.

This removes concurrent map/server coordination and `tools/list_changed` behavior entirely. An application that needs different tools must rebuild or restart its Spring context.

### 3. Keep one remote contract and use a static factory

`RemoteToolClient` remains the only remote integration type. Its nested immutable `ToolDefinition` and `Executor` remain, and a static factory creates an immutable client from `id`, `originKind`, definitions, and executor. The API Fabric/CSE wrapper classes are removed.

This retains concise configuration without maintaining one Java type per upstream system. Requiring anonymous implementations adds boilerplate; keeping two wrappers duplicates validation, storage, and delegation.

### 4. Treat origin as metadata, not dispatch type

`ToolOrigin.Kind` continues to identify `LOCAL`, `API_FABRIC`, `SERVER_COMB`, and `CUSTOM`, but execution always routes through the normalized registration's invoker. The generic client supplies its kind, so adding another source does not require another client implementation.

An arbitrary origin string was considered but would weaken the existing audit contract without a demonstrated requirement.

### 5. Restore the registry package boundary

Startup registration orchestration belongs in `ai.opencode.mcp.registry`; `scanner` contains discovery and schema generation. The registry may remain a replaceable Spring bean, but its supported role is lifecycle initialization and read-only catalog inspection, not application-driven mutation.

### 6. Generate schemas from a resolved Java type graph

Each method schema generation creates a context holding the root properties, a deterministic definition-name allocator, a map from fully resolved Jackson `JavaType` identities to `$defs`, and a set of definitions currently being populated. Object-like types are registered before their properties are traversed, so repeated and cyclic references can immediately emit `$ref`.

Definitions are keyed by resolved type rather than raw class, so `Page<Order>` and `Page<Customer>` receive distinct schemas. Stable sanitized names include generic arguments or a deterministic suffix when simple names collide.

Inline recursive expansion was rejected because it either loops or discards structure. A global process cache was rejected because mapper configuration and generic context can differ between application contexts.

### 7. Use explicit scalar and container mappings before object introspection

An ordered mapping layer handles primitives/wrappers, strings/characters, big numbers, byte arrays, UUID/URI/URL, Java time, locale/currency, optionals, arrays, collections/iterables, maps, enums, and Jackson tree nodes. Container item/value schemas use contained `JavaType` bindings and preserve nested generic structure.

Enum values are obtained through the configured mapper rather than `Enum.toString()`. Formats are emitted only when JSON Schema clients commonly understand them. Maps accept keys Jackson can represent as JSON property names; unsupported concrete key types fail with a path-aware error.

### 8. Make Jackson deserialization introspection authoritative for objects

Object properties come from the configured mapper's deserialization `BeanDescription`. Property names, ignored members, access direction, inherited members, records, any-setter behavior, and resolved generic property types follow the same mapper configuration later used by `convertValue`.

Closed objects receive `additionalProperties: false`; an any-setter or explicitly open JSON value enables additional properties. This favors input accuracy over mirroring serialization-only getters.

### 9. Separate presence, nullability, and conversion

Invocation checks `arguments.containsKey(name)` before reading a value. `@ToolParam(required=...)` controls top-level key presence, while Optional types are always omittable. Primitive schemas exclude null and reject explicit null. Reference types include null only when the accepted Jackson input contract is nullable; absent optional references become null and optional wrappers become empty.

Nested required properties use reliable Jackson creator/property metadata. When Jackson does not provide a reliable required signal, the schema leaves the property optional rather than guessing from a Java reference type.

### 10. Fail unsupported concrete models with path-aware diagnostics

`Object`, Jackson tree values, and unbounded generic values intentionally produce open schemas. A concrete type that cannot be mapped or introspected produces an exception containing tool method, top-level parameter, nested property path, and resolved Java type. Silent `{}` fallback for concrete types is removed.

Polymorphic models driven by custom serializers/deserializers are accepted only when normal Jackson introspection yields an accurate input contract. General `oneOf` synthesis is deferred.

### 11. Audit is observational

Startup registration and invocation attempt a single audit event through a best-effort helper. Audit logger exceptions are reported on an internal SLF4J logger and never recursively audited. Invocation returns the business result or original failure regardless of audit delivery; a registration audit failure does not undo a valid startup registration.

### 12. Test schema and conversion as one contract

Focused tests cover every mapping family, definition reuse, recursion, Jackson configuration, generic binding, presence/null behavior, and diagnostics. Representative values validated by the generated schema are also converted with the same mapper and invoked through the scanner. MCP-facing tests cover capability negotiation, `tools/list`, string/object/native results, and invocation errors.

## Risks / Trade-offs

- [Removing runtime mutation breaks existing callers] → Document restart-based migration and remove APIs before a stable release.
- [Broad schema generation increases implementation complexity] → Separate scalar/container/object mapping, use a per-generation context, and lock behavior with focused parameterized tests.
- [Jackson customization can exceed static introspection] → Fail unsupported concrete cases rather than claiming inaccurate support; document custom serializer limitations.
- [`$defs` names can collide or change] → Use deterministic resolved-type identities and test stable collision handling; consumers must treat definition names as internal references.
- [Best-effort audit can lose events] → Log delivery failures separately; reliable delivery remains the responsibility of an application-provided logger.
- [Full argument/result audit can expose sensitive data] → Retain the prominent README warning and recommend replacing the audit logger; masking policy remains outside this change.

## Migration Plan

1. Replace API Fabric/CSE-specific client beans with `RemoteToolClient` factory calls supplying the existing origin kind and complete startup definitions.
2. Move imports of `McpToolRegistry` back to `ai.opencode.mcp.registry` and remove application calls to runtime `register`/`remove`; configure all tool beans before context initialization.
3. Remove obsolete remote types, runtime mutation methods, removal audit events, and any list-change behavior.
4. Replace schema generation behind the existing annotation API and run schema/conversion compatibility tests.
5. Compile from a clean target, run MCP-facing tests and `mvn clean verify`, then inspect capabilities and JAR contents.
6. Update README package layout, Tools-only scope, startup-fixed catalog, remote migration, supported parameter matrix, limitations, and actual defaults.

Rollback is a source-level revert before publishing a stable release; no persisted data or wire-protocol migration is involved.
