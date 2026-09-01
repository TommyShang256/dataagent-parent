## Context

DataAgent BFF 当前使用官方 MCP Java SDK，通过一个固定 Streamable HTTP 入口发布 Tool。Opencode 已能作为标准 MCP Client 连接该入口，因此 Agent 能力不需要客户端改造。Script 同样可以使用标准 MCP Client，但调用来源不能由可伪造的请求 `_meta` 决定。

## Goals / Non-Goals

**Goals:**

- 不修改 Opencode 源码，保持现有 Agent MCP 配置和调用行为。
- Agent 与 Script 使用标准 MCP 协议访问同一份 Tool 注册。
- 服务端按入口确定可信调用者，并为两类调用者发布隔离目录。
- Tool 的 `allowedCallers` 在目录发布和实际执行两处生效。
- Script 使用无状态标准 MCP 调用，不定义 Skill、Script、父调用和 Trace 来源协议。

**Non-Goals:**

- starter 不实现 OAuth、Token 签发、mTLS 或网关身份认证。
- 不修改 Opencode 的 Skill 定义、Tool Registry、CodeMode 或 MCP Client。
- 不在 MCP Server 内执行或分发 Skill/Script 文件。
- 不在 MCP Server 中定义或校验 Skill/Script 运行时标识。
- 不新增非标准 Script HTTP 工具调用接口。

## Decisions

### 0. Script Runner 部署与职责

父工程新增 `dataagent-runner` 模块。构建产物只包含单文件 `bin/dataagent-runner` 和中文使用手册，解压后只需把 `bin` 加入 `PATH`。Runner 使用 Linux Shell 启动内嵌的 Python 标准库实现，不依赖 jq、curl、pip、JAR 或第三方 Python 包；它是一次性标准 MCP Client，不依赖 Opencode MCP 配置，也不保存 Session、目录、Skill ID、Script ID 或调用历史。

Runner 从 `POD_IP` 与 `POD_PORT` 构造 `http://${POD_IP}:${POD_PORT}/rest/mcp/script`。地址不允许通过 CLI 覆盖，也不回退到 localhost；Opencode 的 4096 端口不参与 Runner 到 BFF 的通信。BFF 需要监听 `0.0.0.0`。

CLI 保持两个入口：`dataagent-runner --list` 查询完整 Script 目录，`dataagent-runner <tool-name> [arguments-json]` 调用工具；第二个参数为 `-` 时从 stdin 读取 JSON。调用前必须遍历全部 `tools/list` 分页并精确检查工具名，工具不存在时不得发送 `tools/call`。

Runner 不提供通用 `--header`。原始 HTTP 业务 Header 继续作为 Tool arguments，由 BFF `headers.business` 映射；MCP 协议 Header 和 Session ID 由 Runner 的协议实现自动管理。未来身份认证 Header 必须由部署环境自动提供，不能混入业务参数。

### 1. 使用两个标准 MCP 入口绑定调用来源

Agent 入口保持 `/rest/mcp`，Script 入口默认为 `/rest/mcp/script`，两者都运行官方 Streamable HTTP MCP transport，分别拥有独立 MCP session。入口创建时绑定固定调用者，Tool call handler 从自身绑定值构造调用来源，不读取客户端 caller。

独立入口同时解决调用来源判定和目录可见性：Opencode 只连接 Agent 入口，不会发现 Script-only Tool；Script Client 只连接 Script 入口，不会发现 Agent-only Tool。

### 2. 一份 Tool 注册按允许调用者发布

`@Tool.allowedCallers` 继续支持 `AGENT`、`SCRIPT` 及二者集合，默认仅 `AGENT`。注册表扫描一次工具后，把 Tool specification 发布到允许调用者对应的 MCP Server。共享 Tool 发布两次，但仍引用同一注册、同一 invoker、同一远程绑定与同一审计实现。

注册失败时按实际发布顺序跨两个 Server 回滚，避免留下部分目录。执行期仍检查绑定调用者属于 `allowedCallers`，防止错误装配或陈旧 specification 绕过策略。

### 3. caller 不再属于客户端元数据

caller 不定义客户端元数据 key，Tool call handler 只使用 MCP endpoint 创建时绑定的值。其他自定义 `_meta` 不参与 caller 解析或兼容性推断。

Script 入口不解析 Skill ID、Script ID、父调用或 Trace 元数据。计划中的 Runner 是用完即退出的无状态 CLI，每次只需建立标准 MCP Client 调用，不维护 Skill/Script 执行链或自定义 `_meta` 协议。审计仅记录 endpoint 绑定的 `AGENT` 或 `SCRIPT`。

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
- [无状态 CLI 不提供 Skill/Script 级身份] → 当前权限粒度明确为 Agent/Script；如后续需要脚本级强身份，由认证层扩展受认证主体上下文。
- [Script endpoint 被匿名访问] → 文档和配置明确要求部署层保护该路径，starter 不制造弱 Token 机制。
- [不修改 Opencode 后失去客户端侧权限预检] → 服务端目录隔离减少不可用 Tool 暴露，执行期授权作为最终安全边界。

## Migration Plan

1. 更新提案，移除 Opencode Runner、Schema 和 caller 注入设计。
2. 在 starter 增加 Script endpoint 配置与两套标准 MCP transport/server。
3. 注册表按 `allowedCallers` 向对应 Server 发布，并由 handler 固定调用者。
4. 删除客户端来源解析和 `ToolCallSource`，审计仅保留 endpoint 绑定 caller。
5. 使用两个官方 MCP Client 完成 Agent、Script、共享 Tool 与越权目录回归。
6. 精确撤销 Opencode 仓库中本需求产生的全部改动。
7. 将 starter、BFF、测试和文档中的配置命名空间统一迁移到 `dataagent.mcp`。

回滚时停止并移除 Script endpoint 配置，再删除 Tool 的 `SCRIPT` 允许项；Agent endpoint 与默认 Agent-only Tool 不受影响。
