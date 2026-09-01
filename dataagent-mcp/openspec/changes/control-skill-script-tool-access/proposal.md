## Why

DataAgent MCP Tool 需要同时供现有 Opencode Agent 与可复用 Skill/Script 使用，并按 Tool 限制允许的调用来源。此前方案要求修改 Opencode、由客户端在 MCP `_meta` 中声明 caller，既增加跨仓库耦合，也无法把客户端声明当作可信身份依据。

## What Changes

- 保持 Opencode 源码不变，现有 MCP 配置继续连接 Agent 入口 `/rest/mcp`。
- 新增标准 MCP Script 入口 `/rest/mcp/script`；Script 使用官方或其他标准 MCP Client 直接完成初始化、`tools/list` 与 `tools/call`。
- 每个入口绑定固定的服务端调用者：Agent 入口固定为 `AGENT`，Script 入口固定为 `SCRIPT`，客户端元数据不参与 caller 判定。
- 每个 Tool 继续通过 `allowedCallers` 声明仅 Agent、仅 Script 或二者均可；启动时只向允许的入口发布，形成服务端隔离目录，并在执行前再次校验。
- Script 调用不要求 Skill ID、Script ID、父调用或 Trace 元数据；无状态 CLI 只需向 Script 入口发送标准 `tools/call`。
- 在 `dataagent-runner/bin/dataagent-runner` 提供可直接执行的无状态 Python Script MCP CLI；支持完整工具目录查询、调用前存在性预检、JSON/stdin 参数和标准 MCP 结果输出。
- Runner 只读取部署注入的 `POD_IP` 与 `POD_PORT`，固定连接 `http://${POD_IP}:${POD_PORT}/rest/mcp/script`，不允许调用方传入 MCP 地址或任意 HTTP Header。
- 两个入口继续使用标准 Streamable HTTP MCP 协议，不增加 Script Token、私有调用协议或 Opencode 专用适配。
- 部署环境必须在网关、Spring Security、OAuth 或 mTLS 层保护 Script 入口；入口路由负责授权来源绑定，不替代客户端身份认证。
- 删除本需求对 `/Users/tommy/projects/opencode` 的全部源码、测试、Schema 与文档改动。

## Capabilities

### New Capabilities

- `skill-script-tool-access`: 定义双标准 MCP 入口、服务端调用来源绑定、Tool 目录隔离、执行期授权和来源审计。
- `dataagent-script-runner`: 定义无状态 Runner 的部署、CLI、工具发现、调用预检、参数与输出协议。

### Modified Capabilities

- `mcp-tool-runtime`：将 starter 配置命名空间从 Agent 框架相关的 `opencode.mcp` 改为框架中立的 `dataagent.mcp`。
- `dataagent-bff-application`：令独立 `mcp-config.yml` 使用 `dataagent.mcp` 配置树，不再以 Opencode 命名配置。

## Impact

- `dataagent-mcp`：增加双 MCP transport/server 装配，按调用者发布 Tool，调整来源解析、审计与测试。
- `dataagent-web`：配置 Script 入口，并用两个官方 MCP Client 完成端到端回归。
- `dataagent-runner`：只保留带 Python shebang 的单文件 MCP Client，仅依赖 Python 3 标准库，不作为 Maven 模块、不生成 ZIP/TAR，也不保留独立使用手册或测试目录；真实 BFF/API Fabric 集成测试由 `dataagent-web` 承担。
- 配置迁移：现有 `opencode.mcp.*` 配置需要直接改为 `dataagent.mcp.*`，不保留双轨兼容绑定。
- `/Users/tommy/projects/opencode`：不保留任何本需求改动；现有 Agent MCP Client 无需升级。
- API Fabric、CSE、JSON、同名 Header/Body 与 multipart 参数绑定行为不变。
