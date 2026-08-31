## Context

变更动机参见 `proposal.md`，可观察行为参见 `specs/remote-tool-routing/spec.md`。当前 scanner 将每个注解方法直接转换为 `ToolRegistration`，其 invoker 通过反射执行 Java 方法。registry 的 MCP call handler 可以取得 `McpSyncServerExchange`，但当前会丢弃它；Servlet transport 也尚未安装 transport context extractor。starter 已使用 Spring MVC，但尚未引入 WebFlux 客户端 API。

固定启动期工具目录仍是核心约束：任何工具加入 MCP Server 之前，都必须完成端点配置与参数绑定校验。API Fabric 使用公共基础 URL；CSE 地址是完整的自定义 scheme URI，不得改写为 HTTP。

## Goals / Non-Goals

**目标：**

- 在启动时把配置和注解方法元数据编译为不可变的远程调用计划。
- 保持每个 Body 字段都是独立描述的工具参数，使 Agent 能理解并生成这些参数。
- 在类型、配置、Schema 和审计边界上区分 Agent 提供的业务 Header 与请求级透传 Header。
- 注解工具未匹配配置端点时，保留现有本地工具行为和通用 `RemoteToolClient` 行为。
- 在支持远程调用上下文的同时，保持公开的单参数 `ToolInvoker` 函数式契约源码兼容。

**非目标：**

- 固定 Header 配置。
- `multipart/form-data`、文件上传、表单编码或流式请求体。
- 根据 HTTP method 或 Java 类型推断参数位置。
- 改写 `cse` URI、实现 CSE 服务发现或提供 CSE 认证。
- 异步 MCP Server 执行、Resources、Prompts 或运行时工具修改。

## Decisions

### 1. 配置键就是端点引用

`@Tool.name` 已经是稳定且对 MCP 可见的标识，因此同时作为端点引用，不增加第二个注解属性。配置结构如下：

```yaml
opencode:
  mcp:
    api-fabric:
      base-url: https://api-fabric.example.com
      endpoints:
        create_order:
          method: POST
          path-template: /tenants/{tenantId}/orders
          query:
            dry_run: dryRun
          headers:
            business:
              X-Biz-Mode: bizMode
    cse:
      endpoints:
        reserve_inventory:
          method: POST
          uri-template: cse://inventory-service/warehouses/{warehouseId}/reservations
          query:
            validate_only: validateOnly
          headers:
            business:
              X-Biz-Mode: bizMode
```

Query 和业务 Header 映射采用 `下游名称: 工具参数名称`。Path 参数由 URI template 中的 `{参数名}` 自动识别，不需要配置；剩余工具参数按原名自动组成 JSON Body。透传 Header 不需要配置，符合规则的入站 Header 默认原样复制。Spring 配置属性使用保持插入顺序的 Map，确保生成的 JSON Body 和诊断信息稳定。

备选方案：为 `@Tool` 增加 `ref`。不采用，因为已确认 name 与 ref 相同，第二个标识可能发生漂移。

### 2. 远程计划只替换调用目标

scanner 继续从注解方法派生工具名称、描述、hints、输入 Schema、参数名称、泛型参数类型和声明返回类型。构建本地元数据后，binder 使用最终工具名称查询端点目录：

- 未匹配：保留反射调用的本地 invoker 和 `LOCAL` origin；
- 匹配 API Fabric：安装编译后的 HTTP invoker，并使用 `API_FABRIC` origin；
- 匹配 CSE：安装编译后的 HTTP invoker；为兼容现有 API，继续使用 `SERVER_COMB` origin kind。

匹配远程端点后绝不执行注解方法体。通用 `RemoteToolClient` 路径保持独立且不变。

备选方案：把配置端点建模为自动生成的 `RemoteToolClient` Bean。不采用，因为这类 client 自带发现得到的 Schema，而本功能需要使用注解 Java 签名作为 Agent 可见契约。

### 3. 模板识别 Path 并由剩余参数自动生成 Body

编译调用计划时按照固定顺序划分工具参数：

1. 从 API Fabric `path-template` 或 CSE `uri-template` 提取全部 `{参数名}` 占位符，并消费同名工具参数作为 Path 参数；
2. 按 Query 配置消费对应工具参数；
3. 按业务 Header 配置消费对应工具参数；
4. 将剩余工具参数按原参数名加入 JSON Object Body。

Path 不支持重命名：模板中的 `{tenantId}` 必须对应工具参数 `tenantId`。同一个来源参数不能同时被 Path、Query 或业务 Header 消费。Query 集合生成重复参数；业务 Header 集合生成重复 Header 值。标量转换使用已配置的 Jackson mapper，使 enum 和 Java time 遵循应用序列化设置。

Body 不需要任何配置。缺失的可选剩余参数被省略；存在且值为 null 的剩余参数写入 JSON null。标量、集合和嵌套结构值以 Jackson tree 形式插入。全部参数都被 Path、Query 或业务 Header 消费时不发送请求体。不提供 Body 字段重命名、根包装参数模式或嵌套 JSON Pointer 映射；接口需要嵌套内容时，使用一个结构化工具参数表达对应字段。

该规则不根据 HTTP method 推断位置。即使是 GET，只要仍有剩余参数，也会生成 Body；不希望 GET 携带 Body 时，必须通过模板、Query 或业务 Header 消费全部参数。

备选方案：显式配置 Path 和 Body 映射。不采用，因为 URI template 已包含 Path 信息，而剩余参数可以无歧义地组成 Body。备选方案：将单一 `request` DTO 绑定为整个 Body。不采用，因为这会隐藏 Agent Loop 需要理解的根级参数。

### 4. 只配置业务 Header，其他 Header 默认透传

业务 Header 是普通工具参数，参与 Schema 生成、必填和 null 处理、参数审计以及下游 Header 序列化。

透传 Header 只来自当前 MCP HTTP 请求，不会成为工具参数，也不会用于端点选择或业务判断。配置中不声明透传列表；除业务 Header 同名项和系统排除项外，所有入站 Header 都保持原名称和全部值复制到下游请求。

Header 名称按不区分大小写的方式比较。入站 Header 与业务 Header 目标同名时，忽略入站值，只使用工具参数生成的业务值。业务 Header 配置不得使用系统排除名称；运行时也不透传这些名称。

系统排除集合固定包含：`Host`、`Content-Length`、`Connection`、`Transfer-Encoding`、`Upgrade`、`Keep-Alive`、`TE`、`Trailer`、`Accept`、`Content-Type`、`Mcp-Session-Id` 和 `Last-Event-ID`。前八项由连接或 connector 控制，`Accept` 和 `Content-Type` 由下游请求构建过程设置，最后两项属于 MCP 协议。运行时拒绝包含 CR 或 LF 的 Header 值。

明确不提供固定 Header 配置。应用自有的客户端行为可以通过应用提供的 WebClient filter 扩展，而不扩大端点配置。

### 5. Transport context 显式传递且仅属于当前请求

Servlet transport 安装 `McpTransportContextExtractor<HttpServletRequest>`。每个 HTTP 请求将系统排除集合之外的 Header 及其全部值复制到不可变、不区分大小写的多值结构，并保存在 starter 自有的 transport context key 下。端点 invoker 再移除当前调用计划的业务 Header 同名项，将其余值原样加入下游请求。

`ToolInvoker` 保持函数式接口和现有抽象方法，同时增加默认的 context-aware overload。registry 处理 MCP 调用时，将 `exchange.transportContext()` 转换为小型 `ToolInvocationContext` 并传给 invoker。现有本地 invoker 和应用 invoker 仍只需实现原方法；编译后的远程 invoker 覆写 context-aware 方法。registry 的审计包装必须同时保留两种调用方式，不能丢失 context。

该方案避免 ThreadLocal、session 级 Header 缓存以及跨请求泄漏。BFF/client 必须在每次 `tools/call` HTTP 请求中携带透传 Header；initialize 请求中的 Header 不会被复用。

备选方案：使用 ThreadLocal 保存 Header。不采用，因为响应式客户端执行过程中不可靠，也更难证明请求隔离。

### 6. API Fabric 和 CSE 共用一条 WebClient 请求管线

增加 Spring WebFlux 客户端库，但不改变 Servlet Server 技术栈。统一的请求构建器应用参数映射，并通过 `WebClient` 发起同步调用，因为当前 MCP Server 是同步模式。

API Fabric URI 先组合已校验的基础 URL 与 path template，再展开 path 参数。CSE 从完整 URI template 开始，并将最终的 `cse://` URI 原样交给 WebClient。starter 暴露可替换的远程 WebClient provider，使 CSE 部署能够提供理解自定义 scheme 的 `ClientHttpConnector`；starter 不模拟服务发现，也不改写地址。

成功响应使用已配置的 `ObjectMapper`，按照方法解析后的泛型返回类型读取并转换 JSON。String 和 void-like 返回值单独处理。`WebClient` 状态异常、connector 错误、超时、URI 错误和转换错误通过现有 MCP error adapter 返回，同时保留原始调用审计失败信息。

备选方案：使用面向 Servlet 的 REST client。不采用，因为 API Fabric 与 CSE 已明确要求使用 WebClient，且 CSE 需要可替换的 connector 边界。

### 7. Server 注册前完成全部校验

registry 添加任何 SDK tool specification 之前，先编译端点目录和全部方法绑定。校验范围包括：空白或非法 URL/method、两个端点类别中的重复引用、没有注解工具的配置引用、URI 占位符没有同名工具参数、Query/业务 Header 引用未知参数、参数位置冲突、重复下游名称、业务 Header 使用系统排除名称，以及不支持的 media 配置。

错误信息必须包含端点类别、引用以及对应映射或 Header。现有 registry rollback 继续作为最终保护，以处理 MCP Server 在校验完成后拒绝 specification 的情况。

### 8. 消费端测试验证外部请求行为

同级 `dataagent-mcp-test` 应用声明注解代理工具，其方法体在被执行时主动失败，并配置具有代表性的 API Fabric 和 CSE 引用。测试专用 WebClient exchange function 捕获下游请求并返回确定的 JSON。断言覆盖 method、URI、path 编码、重复 query 值、业务 Header、默认透传 Header、系统排除 Header、展开 Body、声明返回类型转换、本地 fallback 以及下游失败。

starter 级测试覆盖配置绑定、校验诊断、context 提取、审计隔离、Header 请求隔离，以及 `cse` URI 原样到达 provider。

## Risks / Trade-offs

- [默认 WebClient connector 通常拒绝 `cse` URI] → 保持 URI 不变，并要求 CSE 部署提供可替换的 provider/connector；使用请求捕获客户端验证该边界。
- [context-aware 调用重载可能被包装器意外绕过] → 为 registry 审计包装和实际 MCP call handler 中的远程调用增加契约测试。
- [业务 Header 参数可能包含敏感值，当前审计会记录 arguments] → 保留现有已记录的审计契约，明确排除透传值，并建议应用对敏感业务参数进行审计脱敏。
- [默认透传会把调用方提供的大多数 Header 带到下游] → 明确该行为只适用于受信任的 BFF 调用链，固定排除连接、内容协商和 MCP 协议 Header，并确保透传值不进入审计。
- [严格匹配配置引用会阻止保留未使用的端点项] → 优先选择启动期拼写错误检测；应用只应配置实际暴露的工具。
- [同步阻塞会在慢速下游调用期间占用请求线程] → 复用已配置的 timeout，记录该行为，并将异步 Server 改造留在范围之外。
- [展开 Body 参数可能生成很大的 Schema] → Agent 可用性优先；具有业务意义的嵌套内容仍可使用结构化参数表达。

## Migration Plan

1. 增加 WebClient 支持、端点属性、配置元数据、请求 context 和绑定组件；未配置端点时，现有应用继续使用本地工具和通用远程工具。
2. 使用现有注解工具名称作为 key，增加 API Fabric 或 CSE 端点配置；应用下次重启后，匹配的注解工具成为远程代理。
3. 启用 `cse` 引用之前，提供运行环境对应的 WebClient connector/provider。
4. 验证消费端模块，并确保部署后的每次 MCP 工具调用都携带所需请求 Header。
5. 回滚时删除端点配置；重启后，相同注解方法恢复本地调用。

## Open Questions

无。本次设计不遗留会改变实现范围的问题；multipart 文件上传作为明确的后续待办处理。
