## 1. Tools-Only Public Surface

- [x] 1.1 Add the immutable generic `RemoteToolClient` factory with validation for id, origin kind, startup definitions, and executor; verify focused tests cover valid construction, immutability, and every invalid input
- [x] 1.2 Migrate tests and examples to the generic factory and remove all API Fabric/CSE-specific client and legacy remote model types; verify clean compilation and JAR inspection contain only the generic remote integration API
- [x] 1.3 Move `McpToolRegistry` to the registry package, make it startup/read-only, and remove public runtime register/remove methods, removal events, and list-change behavior; verify API-level tests cannot mutate the catalog after initialization
- [x] 1.4 Configure and test a Tools-only MCP capability surface; verify initialization advertises Tools, excludes Resources/Prompts/Completions, and successful `tools/list` plus `tools/call` requests work

## 2. JSON Schema Type Graph

- [x] 2.1 Introduce a per-method schema context with resolved-`JavaType` identity, deterministic `$defs` naming, `$ref` reuse, and cycle tracking; verify self-recursive, mutually recursive, repeated, and colliding generic types produce stable finite schemas
- [x] 2.2 Implement scalar mappings for primitives, wrappers, strings, characters, big numbers, byte arrays, UUID/URI/URL, Java time, locale/currency, and Jackson-serialized enums; verify parameterized tests assert JSON types, formats, enum values, and nullability
- [x] 2.3 Implement arrays, collections, sets, iterables, maps, Jackson tree values, standard Optional variants, nested containers, bounded variables/wildcards, and concrete generic bindings; verify deeply nested and generic-bean schemas retain resolved item/value types
- [x] 2.4 Generate record and bean definitions from Jackson deserialization introspection, honoring inherited properties, configured names, ignored/access-controlled properties, any-setters, nested generics, and closed-object behavior; verify mapper-customized fixture schemas match accepted input properties
- [x] 2.5 Implement key-presence, required, and explicit-null semantics for top-level parameters and reliable nested Jackson metadata; verify missing required values, omitted optional references, all Optional variants, null references, null primitives, and invalid optional primitive declarations behave consistently in schema and invocation
- [x] 2.6 Restrict open `{}` schemas to intentional open values and add path-aware failures for unsupported concrete types or map keys; verify diagnostics contain the tool, parameter/property path, and resolved Java type

## 3. Invocation and Audit Contract

- [x] 3.1 Update argument conversion to use key presence and the configured mapper consistently with generated schemas; verify representative valid schema instances convert and invoke successfully while schema-invalid instances return MCP tool errors
- [x] 3.2 Exercise the MCP-facing call handler for string, object, null, native MCP result, serialization failure, and invocation exception responses; verify returned content and `isError` values match the capability spec
- [x] 3.3 Make startup registration and invocation audit delivery best-effort and non-recursive, removing removal audit behavior; verify a throwing logger cannot invalidate registration, change a successful result, or replace the original tool failure

## 4. Startup and Documentation

- [x] 4.1 Validate the complete discovered catalog before server registration and fail startup on duplicate names or rejected registrations; verify no application context reports a partially valid catalog
- [x] 4.2 Extend context tests for default activation, explicit disablement, endpoint normalization, generic remote startup discovery, and custom infrastructure replacement; verify the auto-configuration test suite passes
- [x] 4.3 Update README package layout, Tools-only scope, fixed startup catalog, generic remote migration, actual defaults, supported parameter matrix, schema limitations, synchronous execution caveat, and unmasked-audit warning; verify every documented public type exists in the built JAR

## 5. Final Verification

- [x] 5.1 Run `mvn clean verify` with Java 21 release compilation and verify all unit, context, schema/conversion, and MCP-facing tests pass without stale target classes
- [x] 5.2 Inspect the final Git diff, public API, server capabilities, and JAR contents to verify no unrelated changes, runtime mutation APIs, obsolete remote classes, resource/prompt abstractions, or stale package names remain
