## Purpose

在不修改 Opencode 的前提下，为 Agent 与 Skill/Script 提供隔离的标准 MCP Tool 目录和服务端最终授权。

## ADDED Requirements

### Requirement: 每个 Tool 必须声明允许调用者

MCP Server SHALL 为每个 Tool 保存非空的允许调用者集合，集合至少支持 `AGENT` 与 `SCRIPT`。未显式声明的 Tool MUST 默认仅允许 `AGENT`。

#### Scenario: 三种调用范围分别生效
- **WHEN** 三个 Tool 分别配置 `{AGENT}`、`{SCRIPT}` 与 `{AGENT, SCRIPT}`
- **THEN** 系统分别只允许 Agent、只允许 Script、允许二者调用

#### Scenario: 非法调用者策略在发布前失败
- **WHEN** Tool 的允许调用者集合为空
- **THEN** MCP Server 在发布任何 Tool 目录前失败
- **AND** 英文错误包含 Tool 名称与无效策略

### Requirement: Agent 与 Script 必须使用独立标准 MCP 入口

系统 SHALL 在 Agent endpoint 与 Script endpoint 分别运行标准 Streamable HTTP MCP transport。Agent endpoint MUST 固定绑定 `AGENT`，Script endpoint MUST 固定绑定 `SCRIPT`；调用者 MUST NOT 由客户端请求参数或 `_meta` 决定。

#### Scenario: 现有 Opencode 无需修改
- **WHEN** Opencode 使用现有 MCP Client 连接 Agent endpoint
- **THEN** 初始化、`tools/list` 与 `tools/call` 继续成功
- **AND** Opencode 不需要解析私有权限字段或注入 caller

#### Scenario: Script 使用标准 MCP Client
- **WHEN** Script 使用官方或兼容 MCP Client 连接 Script endpoint
- **THEN** 初始化、`tools/list` 与 `tools/call` 按标准协议完成
- **AND** 不需要 Script 专用传输 Token 或私有 HTTP API

### Requirement: 服务端目录必须按调用者隔离

注册表 SHALL 只把 Tool 发布到其 `allowedCallers` 包含的 MCP Server。Agent-only Tool MUST 只出现在 Agent 目录，Script-only Tool MUST 只出现在 Script 目录，共享 Tool MUST 出现在两个目录。

#### Scenario: 两个目录展示权限矩阵
- **WHEN** Tool 目录包含 Agent-only、Script-only 与共享 Tool
- **THEN** Agent `tools/list` 返回 Agent-only 与共享 Tool
- **AND** Script `tools/list` 返回 Script-only 与共享 Tool

#### Scenario: 发布失败完整回滚
- **WHEN** 任一 Tool 向任一允许入口发布失败
- **THEN** 已向两个入口发布的本次 Tool specification 按逆序移除
- **AND** 固定注册目录保持为空

### Requirement: BFF 必须执行最终调用者授权

BFF SHALL 在本地 Tool 方法、API Fabric 或 CSE 执行前，重新检查 endpoint 绑定调用者属于 Tool 的允许集合。

#### Scenario: 错误入口不能执行 Tool
- **WHEN** 错误装配或陈旧 specification 尝试以 Agent 执行 Script-only Tool，或以 Script 执行 Agent-only Tool
- **THEN** BFF 返回英文权限错误
- **AND** Tool invoker 与下游请求均不执行

### Requirement: 客户端不得覆盖调用者

`ai.opencode.dataagent/caller` SHALL 是保留元数据键。任何客户端发送该键时，BFF MUST 拒绝调用；错误 MUST 说明调用者由 MCP endpoint 决定。

#### Scenario: Script 伪造 caller 被拒绝
- **WHEN** Agent 或 Script 请求在 `_meta` 中声明 caller
- **THEN** BFF 在业务处理器前拒绝请求
- **AND** endpoint 的绑定调用者保持不变

### Requirement: Script 来源链必须独立于业务参数

Script 调用 SHALL 在 `_meta` 中提供 Skill ID、Script ID、父调用 ID 与 Trace ID。Agent 调用 MUST NOT 提供这些字段。来源字段 MUST NOT 进入 Tool arguments 或 API Fabric/CSE 的 Path、Query、Header、JSON Body 与 multipart part。

#### Scenario: Script 完整来源被审计
- **WHEN** Script endpoint 收到完整来源链并成功调用共享或 Script-only Tool
- **THEN** 审计记录 caller 为 `SCRIPT` 及全部链路字段
- **AND** 业务参数保持原 Schema

#### Scenario: Script 来源不完整被拒绝
- **WHEN** Script endpoint 缺少任一来源链字段
- **THEN** BFF 在 invoker 前返回英文错误

#### Scenario: Agent 携带 Script 来源被拒绝
- **WHEN** Agent endpoint 请求携带任一 Script 来源字段
- **THEN** BFF 在 invoker 前返回英文错误

### Requirement: 入口保护必须保持外置

starter SHALL NOT 实现 Script 专用 Token。部署文档 MUST 说明由网关、Spring Security、OAuth、mTLS 或网络策略分别保护 Agent 与 Script endpoint。

#### Scenario: 部署层认证与 Tool 授权组合
- **WHEN** 生产部署保护 Script endpoint
- **THEN** 认证层先确认 Client 可以访问该路径
- **AND** MCP Server 再按 `SCRIPT` 执行 Tool 目录与运行期授权

### Requirement: 端到端回归必须覆盖双入口

项目 SHALL 使用两个标准 MCP Client、本地 BFF 与模拟下游完成自动化测试，并验证现有 Opencode Agent Client 无需源码改动。

#### Scenario: 合法调用均成功
- **WHEN** Agent 调用 Agent-only/共享 Tool，Script 调用 Script-only/共享 Tool
- **THEN** BFF 执行对应 Tool并返回预期结果

#### Scenario: 越权能力不可发现且不可执行
- **WHEN** 两个 Client 分别列出目录并尝试构造错误调用
- **THEN** 不允许的 Tool 不在对应目录中
- **AND** 服务端运行期防御测试证明 invoker 和下游均未执行

#### Scenario: 既有 MCP 能力无回归
- **WHEN** 执行父工程完整验证
- **THEN** JSON、同名 Header/Body、multipart、API Fabric、CSE、JavaDoc、覆盖率和严格 OpenSpec 门禁全部通过
