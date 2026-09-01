## MODIFIED Requirements

### Requirement: Conditional MCP server activation
The starter SHALL create stateful Streamable HTTP MCP endpoints in a servlet web application when `dataagent.mcp.enabled` is true or absent, and SHALL not create the MCP infrastructure when the property is false. The starter SHALL use the framework-neutral `dataagent.mcp` configuration namespace and SHALL NOT retain an `opencode.mcp` compatibility binding.

#### Scenario: Default activation
- **WHEN** a servlet Spring Boot application includes the starter without setting `dataagent.mcp.enabled`
- **THEN** the application exposes the configured MCP endpoints

#### Scenario: Explicit deactivation
- **WHEN** the application sets `dataagent.mcp.enabled=false`
- **THEN** the starter does not create the MCP transport, server, scanner, or registry

#### Scenario: 配置命名不绑定 Agent 框架
- **WHEN** 开发者查看 starter 配置元数据或配置 BFF
- **THEN** 所有 MCP 配置键以 `dataagent.mcp` 开头
- **AND** starter 不绑定 `opencode.mcp` 配置树
