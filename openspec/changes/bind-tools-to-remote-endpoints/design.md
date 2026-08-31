## Context

变更动机参见 `proposal.md`，可观察行为参见 `specs/remote-tool-routing/spec.md`。当前 scanner 将每个注解方法直接转换为 `ToolRegistration`，其 invoker 通过反射执行 Java 方法。registry 的 MCP call handler 可以取得 `McpSyncServerExchange`，但当前会丢弃它；Servlet transport 也尚未安装 transport context extractor。starter 已使用 Spring MVC，但尚未引入 WebFlux 客户端 API。

固定启动期工具目录仍是核心约束：任何工具加入 MCP Server 之前，都必须完成端点配置与参数绑定校验。API Fabric 使用公共基础 URL；CSE 地址是完整的自定义 scheme URI，不得改写为 HTTP。

## Goals / Non-Goals

**目标：**

- 在启动时把配置和注解方法元数据编译为不可变的远程调用计划。
- 保持每个 Body 字段都是独立描述的工具参数，使 Agent 能理解并生成这些参数。
- 在类型、配置、Schema 和审计边界上区分 Agent 提供的业务 Header 与请求级透传 Header。
- 注解工具未匹配配置端点时，保留现有本地工具行为。
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

- 未匹配：保留反射调用的本地 invoker，并将最终类型设为 `Tool.Type.LOCAL`；
- 匹配 API Fabric：安装编译后的 HTTP invoker，并将最终类型设为 `Tool.Type.API_FABRIC`；
- 匹配 CSE：安装编译后的 HTTP invoker，并将最终类型设为 `Tool.Type.CSE`。

匹配远程端点后绝不执行注解方法体。工具只通过 `@Tool` 声明，scanner 不再同时支持第二套编程式工具目录。

备选方案：另外保留一套自带 Schema 和执行器的编程式工具客户端。不采用，因为当前没有实际消费场景，且会引入第二套工具定义与扫描流程。

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

`ToolInvoker` 保持函数式接口和现有抽象方法，同时增加默认的 Header-aware overload。registry 处理 MCP 调用时，从 `exchange.transportContext()` 取得不可变的多值 Header `Map` 并传给 invoker。现有本地 invoker 和应用 invoker 仍只需实现原方法；编译后的远程 invoker 覆写 Header-aware 方法。registry 的审计包装必须同时保留两种调用方式，不能丢失 Header。请求上下文当前只承载 Header，因此不额外声明 `ToolInvocationContext` 包装类型；提取器类名直接作为 starter 内部 transport context key，不再公开额外常量。

该方案避免 ThreadLocal、session 级 Header 缓存以及跨请求泄漏。BFF/client 必须在每次 `tools/call` HTTP 请求中携带透传 Header；initialize 请求中的 Header 不会被复用。

备选方案：使用 ThreadLocal 保存 Header。不采用，因为响应式客户端执行过程中不可靠，也更难证明请求隔离。

### 5.1 工具绑定类别内联到 Tool

`ToolOrigin` 同时保存类别与 `sourceId`，但远程 `sourceId` 与 `@Tool.name`/端点 ref 重复，本地类名也只用于日志，不参与路由或调用。删除该包装类型，在 `Tool` 注解类型中声明嵌套枚举 `Type`，并由 `ToolRegistration` 保存扫描和端点绑定后解析出的最终类型。

`Tool.Type` 包含 `LOCAL`、`API_FABRIC`、`CSE` 和 `CUSTOM`。前三项对应 starter 内置路径；`CUSTOM` 仅供公共 `RemoteToolEndpointHandler` 扩展实现标识自定义远程类别。类型不作为 `@Tool` 的可配置注解属性，避免注解值与端点配置发生冲突。审计事件只记录最终类型，不再记录重复的来源标识；共享绑定器按最终类型选择 WebClient 或 RestOperations 执行通道。

备选方案：在 `@Tool` 上公开 `type` 属性。不采用，因为同一个注解工具是否远程及远程类别由配置绑定结果决定，手工属性会形成第二个不一致的事实来源。

### 5.2 工具行为属性直接进入注册模型

工具只通过 `@Tool` 声明后，`ToolHints` 不再承担跨来源标准化职责，只把 `readOnly`、`destructive`、
`idempotent` 和 `openWorld` 四个布尔值从注解复制到 `ToolRegistration`，随后由 registry 再逐项读取生成
SDK `ToolAnnotations`。该 record 没有校验、计算或独立扩展策略，因此删除 `ToolHints`，由 scanner 将注解值
直接写入 `ToolRegistration`，registry 直接读取四个字段。

不在 `ToolRegistration` 中保存 `Tool` 注解实例。注解是扫描输入，且空工具名仍需结合 Java 方法名解析；运行期
注册模型只保存解析后的稳定值。远程端点绑定只替换 invoker 和最终类型，复制注册信息时完整保留四个行为属性。

### 6. API Fabric 和 CSE 共享绑定规则但使用独立客户端

增加 Spring WebFlux 客户端库但不改变 Servlet Server 技术栈，仅由 API Fabric 使用 WebClient。统一绑定工厂继续
负责编译 URI、Query、Header、Body 和返回类型计划，执行阶段显式按最终 `Tool.Type` 选择客户端：
`API_FABRIC` 进入 WebClient 分支，`CSE` 进入 RestOperations 分支。

API Fabric URI 先组合已校验的基础 URL 与 path template，再展开 path 参数，并由独立 WebClient 执行。CSE 从完整
URI template 开始，将最终 `cse://` URI、method、`HttpEntity` 和泛型返回类型交给 Spring `RestOperations`。
CSE 不再经过 WebClient。

新增公共 `CseRestTemplateProvider`，应用通过它返回公司环境增强后的 `RestOperations`。starter 不依赖 Apache
ServiceComb，也不猜测公司内部服务发现、Header 或超时实现；默认 provider 是明确的占位实现，只要存在 CSE ref，
handler 在绑定期取 client 时立即失败，避免发布一个必然运行期失败的工具。应用未配置 CSE ref 时占位 provider
不影响本地工具和 API Fabric。

API Fabric 成功响应继续使用已配置的 `ObjectMapper` 按泛型返回类型转换。CSE 使用
`ParameterizedTypeReference.forType` 将注解方法的完整返回类型交给 `RestOperations`。两类客户端的状态异常、
连接错误和转换错误都通过现有 MCP error adapter 返回；API Fabric 使用 starter 请求超时，CSE 超时由应用提供的
RestOperations 实现负责。

备选方案：直接依赖 Apache ServiceComb `RestTemplateBuilder`。不采用，因为公司内部 CSE 实现与外部版本不同，
starter 只应定义 Spring RestTemplate 扩展边界。备选方案：为 CSE 保留 WebClient provider。不采用，因为已明确
CSE 不使用 WebClient。

### 6.1 端点类别使用公共 SPI 并可独立替换

增加公共 `RemoteToolEndpointHandler` 接口。每个实现负责声明自己的类别名称和端点引用，并把匹配的注解工具编译为远程 `ToolRegistration`。默认提供 `ApiFabricToolEndpointHandler` 和 `CseToolEndpointHandler` 两个实现；URI 组合、类别专属配置校验和最终工具类型选择由各自实现负责。

`McpToolScanner` 汇总全部 handler 的引用，统一检查跨实现重复 ref、配置 ref 没有对应注解工具等目录级约束，再把匹配工具交给唯一 handler；没有匹配项的工具保持本地调用。请求参数划分、Header、Body、WebClient 执行和响应转换下沉到包内共享的调用计划工厂，避免两个 handler 重复实现协议行为。

项目尚未上线，不保留原先同时处理两类端点的 `RemoteToolEndpointBinder` 兼容适配器；测试和自动配置直接使用 scanner 及公共 handler SPI，避免形成重复入口。

自动配置按具体默认实现分别使用缺失条件，而不是对公共接口整体使用缺失条件。因此应用替换 API Fabric handler 时，默认 CSE handler 仍会创建，反之亦然；应用也可以增加新的 handler 类型，并自动参与组合绑定及重复 ref 校验。

为减少没有独立策略价值的类型，scanner 直接接收全部 `RemoteToolEndpointHandler` 并完成目录汇总、未知 ref 校验和单工具绑定。不再保留只做转发的 `ToolEndpointBinder`、`CompositeToolEndpointBinder` 和仅包装 Method/Registration 的 `ToolMethodRegistration`。handler 的绑定方法直接接收 Java `Method` 与扫描得到的 `ToolRegistration`。这一调整保持端点 SPI 可替换性，同时减少三种生产类型和一层调用链。

代码结构遵循“存在独立职责才保留类型或方法”的原则：删除单字段远程响应包装类型和无逻辑显式构造器，但不把请求编译、Schema、审计等不同职责机械合并。所有 Java 源文件的顶层类型声明使用说明其职责的 JavaDoc，并统一标注 `@author beining.shang` 与 `@since 2026-08-31`。

备选方案：保留一个同时处理 API Fabric 和 CSE 的 binder，仅替换 WebClient provider。不采用，因为无法单独替换某一端点类别的配置解释、校验和 URI 构造。备选方案：对公共接口使用单一 `@ConditionalOnMissingBean`。不采用，因为任意自定义实现都会错误地关闭其他默认类别。

### 6.2 收敛 remote 包内部结构

`RemoteHeaderPolicy` 与 `ServletToolContextExtractor` 都负责同一条请求 Header 边界：前者定义系统排除和 CR/LF
规则，后者按规则提取当前 Servlet 请求。将二者合并为 `RemoteRequestHeaders`，由该类实现 SDK transport
extractor，并以包级静态方法向共享远程绑定代码提供相同策略。排除集合和校验方法不再成为独立公共 API，
transport context key 使用合并后类名，registry 仍只读取不可变多值 Header 映射。

共享组件从 `RemoteToolInvocationFactory` 重命名为 `RemoteToolBindingFactory`，准确表达其职责是把注解方法和端点
配置编译为远程 `ToolRegistration`。该组件继续以内部嵌套 invoker 保存不可变调用计划，不拆成额外的 compiler、
plan 和 executor 类型。绑定期预计算不区分大小写的业务 Header 名称，运行期只应用当前请求参数和 Header；内部
构造器使用 Lombok 消除样板，删除响应式调用不可能产生的 null 响应分支。

API Fabric handler 在组合 `base-url` 前验证 `path-template` 必须以单个 `/` 开头，拒绝绝对 URI、
scheme-relative URI 和缺少根斜杠的模板。API Fabric 与 CSE 两个 handler 仍作为独立默认类存在；
`RemoteToolEndpointHandler` 负责替换配置解释和绑定策略；API Fabric WebClient 与
`CseRestTemplateProvider` 与独立 API Fabric WebClient 分别提供两类客户端，不再保留按
`Tool.Type` 返回 WebClient 的公共 provider；`Tool.Type` 改由共享绑定器用于选择
WebClient 或 RestOperations 执行分支。

备选方案：合并 API Fabric/CSE handler。不采用，因为会恢复类型分支并破坏独立替换边界。备选方案：要求自定义
CSE handler 重写整个请求管线。不采用，因为应用只需要补充公司内部 RestTemplate 实现。
备选方案：把共享绑定组件拆成多个顶层类型。不采用，因为验证、参数划分和执行共同构成一次配置到 invoker 的编译。

### 7. Server 注册前完成全部校验

registry 添加任何 SDK tool specification 之前，先编译端点目录和全部方法绑定。校验范围包括：空白或非法 URL/method、两个端点类别中的重复引用、没有注解工具的配置引用、URI 占位符没有同名工具参数、Query/业务 Header 引用未知参数、参数位置冲突、重复下游名称、业务 Header 使用系统排除名称，以及不支持的 media 配置。

错误信息必须包含端点类别、引用以及对应映射或 Header。现有 registry rollback 继续作为最终保护，以处理 MCP Server 在校验完成后拒绝 specification 的情况。

### 8. 消费端测试验证外部请求行为

同级 `dataagent-mcp-test` 应用声明注解代理工具，其方法体在被执行时主动失败，并配置具有代表性的 API Fabric 和 CSE 引用。测试分别捕获 WebClient 和 RestOperations 请求并返回确定的 JSON。断言覆盖 method、URI、path 编码、重复 query 值、业务 Header、默认透传 Header、系统排除 Header、展开 Body、声明返回类型转换、本地 fallback 以及下游失败。

starter 级测试覆盖配置绑定、校验诊断、context 提取、审计隔离、Header 请求隔离，以及 `cse` URI 原样到达 provider。

### 9. 生产运行时字符串统一使用英文

starter 自身定义的 SLF4J 日志模板、字段名、异常消息和断言消息统一使用英文。异常可能被 Spring 或其他框架自动
记录，因此只检查显式 `log.*` 调用不足以保证最终日志没有中文。测试扫描 starter 及消费端生产 Java 源码的全部
字符串字面量，拒绝其中包含中文字符，防止中文固定文本通过任何运行时路径进入日志。

工具名称、调用参数、调用结果和下游异常属于外部动态值，必须保持原始业务含义，不做翻译、替换或丢弃。该约束
不改变中文 JavaDoc、注释、README 和 OpenSpec；源码扫描只解析字符串字面量，不把注释误判为运行时内容。

## Risks / Trade-offs

- [starter 无法猜测公司内部 CSE RestTemplate 实现] → 只提供 `CseRestTemplateProvider` 边界，配置 CSE ref 但未提供实现时在发布目录前失败。
- [公共 handler 允许应用增加端点类别，可能产生跨实现 ref 冲突] → 由 scanner 在目录发布前集中检查，禁止按 Bean 顺序静默覆盖。
- [context-aware 调用重载可能被包装器意外绕过] → 为 registry 审计包装和实际 MCP call handler 中的远程调用增加契约测试。
- [业务 Header 参数可能包含敏感值，当前审计会记录 arguments] → 保留现有已记录的审计契约，明确排除透传值，并建议应用对敏感业务参数进行审计脱敏。
- [默认透传会把调用方提供的大多数 Header 带到下游] → 明确该行为只适用于受信任的 BFF 调用链，固定排除连接、内容协商和 MCP 协议 Header，并确保透传值不进入审计。
- [严格匹配配置引用会阻止保留未使用的端点项] → 优先选择启动期拼写错误检测；应用只应配置实际暴露的工具。
- [同步阻塞会在慢速下游调用期间占用请求线程] → 复用已配置的 timeout，记录该行为，并将异步 Server 改造留在范围之外。
- [展开 Body 参数可能生成很大的 Schema] → Agent 可用性优先；具有业务意义的嵌套内容仍可使用结构化参数表达。

## Migration Plan

1. 增加 WebClient 支持、端点属性、配置元数据、请求 context 和绑定组件；未配置端点时，现有应用继续使用本地注解工具。
2. 使用现有注解工具名称作为 key，增加 API Fabric 或 CSE 端点配置；应用下次重启后，匹配的注解工具成为远程代理。
3. 启用 `cse` 引用之前，通过 `CseRestTemplateProvider` 提供运行环境对应的 `RestOperations`。
4. 验证消费端模块，并确保部署后的每次 MCP 工具调用都携带所需请求 Header。
5. 回滚时删除端点配置；重启后，相同注解方法恢复本地调用。

## Open Questions

无。本次设计不遗留会改变实现范围的问题；multipart 文件上传作为明确的后续待办处理。
