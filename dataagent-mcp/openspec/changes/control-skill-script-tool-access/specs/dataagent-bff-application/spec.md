## MODIFIED Requirements

### Requirement: BFF 配置可外部化
BFF SHALL 将 MCP 服务、API Fabric 基础地址和示例远程端点路径集中保存在独立的 `mcp-config.yml` 中，配置树 SHALL 使用框架中立的 `dataagent.mcp` 命名空间，`application.yml` SHALL NOT 直接声明 MCP 配置，仓库 SHALL NOT 包含真实环境凭据、令牌或公司环境专用地址。

#### Scenario: 应用导入独立 MCP 配置
- **WHEN** BFF 使用默认 classpath 配置启动
- **THEN** `application.yml` 显式导入 `mcp-config.yml`
- **AND** MCP 服务与远程工具配置由 `dataagent.mcp` 配置树完成绑定
- **AND** `application.yml` 不包含 MCP 配置树

#### Scenario: 配置文件不以 Agent 框架命名
- **WHEN** 开发者检查 `mcp-config.yml`
- **THEN** 配置文件顶层使用 `dataagent`
- **AND** 配置文件不包含 `opencode` 顶层配置

#### Scenario: 本地覆盖远程地址
- **WHEN** 开发者通过环境变量或 Spring 配置覆盖 API Fabric 基础地址
- **THEN** BFF 对工具调用使用覆盖后的地址
- **AND** 无需重新编译应用
