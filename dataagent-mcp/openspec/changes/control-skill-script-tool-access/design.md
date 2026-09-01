## Context

DataAgent BFF 当前使用官方 MCP Java SDK，通过一个固定 Streamable HTTP 入口发布 Tool。Opencode 已能作为标准 MCP Client 连接该入口，因此 Agent 能力不需要客户端改造。Script 同样可以使用标准 MCP Client，但调用来源不能由可伪造的请求 `_meta` 决定。

## Goals / Non-Goals

**Goals:**

- 不修改 Opencode 源码，保持现有 Agent MCP 配置和调用行为。
- Agent 与 Script 使用标准 MCP 协议访问同一份 Tool 注册。
- 服务端按入口确定可信调用者，并为两类调用者发布隔离目录。
- Tool 的 `allowedCallers` 在目录发布和实际执行两处生效。
- Script 的 Skill、Script、父调用和 Trace 上下文不污染业务参数或下游请求。

**Non-Goals:**

- starter 不实现 OAuth、Token 签发、mTLS 或网关身份认证。
- 不修改 Opencode 的 Skill 定义、Tool Registry、CodeMode 或 MCP Client。
- 不在 MCP Server 内执行或分发 Skill/Script 文件。
- 不把客户端提供的 Skill/Script ID 当作已认证身份；这些字段只用于审计关联。
- 不新增非标准 Script HTTP 工具调用接口。

## Decisions

### 1. 使用两个标准 MCP 入口绑定调用来源

Agent 入口保持 `/rest/mcp`，Script 入口默认为 `/rest/mcp/script`，两者都运行官方 Streamable HTTP MCP transport，分别拥有独立 MCP session。入口创建时绑定固定调用者，Tool call handler 从自身绑定值构造调用来源，不读取客户端 caller。

独立入口同时解决调用来源判定和目录可见性：Opencode 只连接 Agent 入口，不会发现 Script-only Tool；Script Client 只连接 Script 入口，不会发现 Agent-only Tool。

### 2. 一份 Tool 注册按允许调用者发布

`@Tool.allowedCallers` 继续支持 `AGENT`、`SCRIPT` 及二者集合，默认仅 `AGENT`。注册表扫描一次工具后，把 Tool specification 发布到允许调用者对应的 MCP Server。共享 Tool 发布两次，但仍引用同一注册、同一 invoker、同一远程绑定与同一审计实现。

注册失败时按实际发布顺序跨两个 Server 回滚，避免留下部分目录。执行期仍检查绑定调用者属于 `allowedCallers`，防止错误装配或陈旧 specification 绕过策略。

### 3. caller 不再属于客户端元数据

`ai.opencode.dataagent/caller` 成为保留键。客户端发送该键时调用失败，错误明确说明 caller 由 MCP endpoint 决定。缺少该键是正常标准调用，不再代表兼容性推断。

Script 入口要求 `_meta` 提供 `skill-id`、`script-id`、`parent-call-id` 与 `trace-id`，用于完整审计。Agent 入口不得携带这些 Script 链路字段。上述字段不进入 Tool arguments、Header 透传或 API Fabric/CSE 参数绑定。

### 4. Opencode 零改造，Script 独立使用标准 Client

Opencode 保持现有配置：

```text
http://host/rest/mcp
```

Skill/Script 进程使用任意标准 MCP SDK连接：

```text
http://host/rest/mcp/script
```

Script 自己维护 MCP session 并发送标准 `tools/call`。DataAgent 不要求 Opencode 注入内部来源、派生私有目录或运行 Script Runner。

### 5. 入口隔离是授权绑定，不是身份认证

Script endpoint 必须由部署层限制只有可信 Script workload 可达。推荐网关 OAuth scope、Spring Security authority、mTLS 或网络策略；部署层可以为两个路径配置不同规则。starter 不增加 Script 专用 Token 或环境变量。

如果两个入口都匿名暴露，任意客户端都可以主动访问 Script endpoint。此时 Tool 目录与执行授权仍按入口正确工作，但不能证明请求来自可信 Script。因此生产部署不能把“路径不同”等同于“身份已认证”。

### 6. 配置命名空间保持 Agent 框架中立

starter 与 BFF 统一使用 `dataagent.mcp` 作为 Spring 配置命名空间，`mcp-config.yml` 顶层使用 `dataagent`，不再使用 `opencode`。Opencode 只是可连接标准 MCP 入口的客户端之一，不应进入服务端配置契约。

项目尚未上线，因此直接替换旧的 `opencode.mcp` 命名，不保留别名、兼容绑定或双配置优先级，避免把已移除的框架耦合继续固化为公共配置 API。

## Risks / Trade-offs

- [两个 MCP endpoint 增加一个 transport 和 server 实例] → 两者共享同一 Tool 注册与 invoker，只隔离协议 session 和目录。
- [共享 Tool specification 发布两次] → 启动审计按逻辑 Tool 记录一次，发布回滚按 Server/Tool 对执行。
- [Script 可伪造其他 Skill/Script ID] → 这些字段仅用于审计，不参与身份认证；需要脚本级强身份时由认证层把身份映射到独立入口或后续扩展受认证主体上下文。
- [Script endpoint 被匿名访问] → 文档和配置明确要求部署层保护该路径，starter 不制造弱 Token 机制。
- [不修改 Opencode 后失去客户端侧权限预检] → 服务端目录隔离减少不可用 Tool 暴露，执行期授权作为最终安全边界。

## Migration Plan

1. 更新提案，移除 Opencode Runner、Schema 和 caller 注入设计。
2. 在 starter 增加 Script endpoint 配置与两套标准 MCP transport/server。
3. 注册表按 `allowedCallers` 向对应 Server 发布，并由 handler 固定调用者。
4. 调整来源解析，拒绝客户端 caller，仅保留 Script 审计链。
5. 使用两个官方 MCP Client 完成 Agent、Script、共享 Tool 与越权目录回归。
6. 精确撤销 Opencode 仓库中本需求产生的全部改动。
7. 将 starter、BFF、测试和文档中的配置命名空间统一迁移到 `dataagent.mcp`。

回滚时停止并移除 Script endpoint 配置，再删除 Tool 的 `SCRIPT` 允许项；Agent endpoint 与默认 Agent-only Tool 不受影响。
