## Why

The starter already proves the end-to-end MCP tool path, but it carries source-specific abstractions and runtime mutation behavior that are unnecessary for a focused Tools server, while its hand-written JSON Schema generator is too weak for common application method signatures. The current pre-release refactor is the right point to reduce the protocol and public API surface while making tool parameter contracts substantially more complete.

## What Changes

- Define the product as a Tools-only MCP server: advertise and implement tool discovery and invocation without adding Resources, Prompts, Completions, or their provider abstractions.
- Discover local annotated tools and generic remote tools during application startup, publish a fixed tool catalog, and remove the public runtime registration/removal API and tool-list change behavior.
- Collapse remote integration onto one generic `RemoteToolClient` contract and one nested tool definition/executor model; remove API Fabric/CSE-specific client types and the unused ServerComb data model.
- Keep source classification as metadata supplied by the remote client rather than encoding each source as another Java type.
- Place scanning/schema generation together and keep startup registration orchestration in its own package, avoiding the current mismatch between package names and responsibilities.
- Expand JSON Schema 2020-12 generation to cover the large majority of practical Java tool parameters: scalar and formatted values, enums, arrays and generic containers, maps, optionals, records and beans, inheritance, nested generics, Jackson-visible properties, required/null semantics, and recursive object graphs.
- Use reusable `$defs`/`$ref` definitions for object graphs and produce explicit diagnostics when a type cannot be represented accurately instead of silently publishing a misleading schema.
- Keep tool invocation audit delivery observational so a custom logger failure cannot change a business result or prevent an otherwise valid startup registration.
- Align README examples and configuration documentation with the final Tools-only API, startup-fixed catalog, schema coverage, and actual defaults.
- **BREAKING**: remove the legacy `ApiFabricClient`, `CseClient`, `ApiFabricRemoteToolClient`, `CseRemoteToolClient`, `RemoteToolDefinition`, and `ServerComb` public types in favor of the single remote-client contract.
- **BREAKING**: remove public runtime `register` and `remove` operations and removal audit events; applications must provide all local and remote tool definitions before singleton initialization completes.
- **BREAKING**: retain `McpToolRegistry` under `ai.opencode.mcp.registry`; consumers of the transient uncommitted `scanner.McpToolRegistry` location must update imports.

## Capabilities

### New Capabilities

- `mcp-tool-runtime`: Defines a Tools-only, startup-fixed MCP catalog with local and generic remote discovery, broad Java-to-JSON-Schema generation, invocation, serialization, and auditing in a Spring Boot servlet application.

### Modified Capabilities

None. The repository does not yet contain baseline capability specifications.

## Impact

- Affected packages: `annotation`, `api`, `audit`, `autoconfigure`, `remote`, `registry`, and `scanner`.
- Public remote-integration and registry-mutation APIs are intentionally reduced and therefore binary/source incompatible with the current snapshot.
- The MCP endpoint, local annotation programming model, startup remote tool discovery, configuration prefix, `tools/list`, and `tools/call` remain supported.
- The server does not advertise Resources or Prompts, and the starter does not expose abstractions for them.
- No new runtime dependencies are introduced; schema generation continues to use the configured Jackson 2 mapper and JSON Schema maps accepted by the MCP SDK.
- Authentication, authorization, secret masking policy, remote refresh, retry/circuit breaking, reactive execution, MCP structured output, output schemas, and Bean Validation integration remain outside this change.
