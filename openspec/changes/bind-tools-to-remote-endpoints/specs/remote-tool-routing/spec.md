## Purpose

为注解 MCP 工具提供确定性的启动期远程端点绑定能力，完整处理请求参数位置和请求级 Header 透传，同时保持固定且仅包含 Tools 的工具目录。

## ADDED Requirements

### Requirement: 注解工具按引用绑定端点
starter SHALL 将每个注解工具最终生效的 `@Tool.name` 作为端点引用。引用只在一个远程端点类别下配置时，系统必须使用配置的远程调用替代该工具的本地方法调用；未配置引用时，系统必须继续执行本地方法。

#### Scenario: API Fabric 引用绑定注解工具
- **WHEN** 注解工具名称与一个 API Fabric 端点引用匹配
- **THEN** `tools/list` 暴露该注解工具的 Schema，且 `tools/call` 调用匹配的 API Fabric 端点而不是 Java 方法体

#### Scenario: CSE 引用绑定注解工具
- **WHEN** 注解工具名称与一个 CSE 端点引用匹配
- **THEN** `tools/list` 暴露该注解工具的 Schema，且 `tools/call` 调用匹配的 CSE 端点而不是 Java 方法体

#### Scenario: 未绑定的注解工具保持本地调用
- **WHEN** 注解工具名称未匹配任何已配置的远程端点引用
- **THEN** 调用按照现有本地工具行为执行注解 Java 方法

### Requirement: API Fabric 端点共享基础 URL
starter SHALL 接受一个 API Fabric 基础 URL，并为每个引用接受 HTTP method 和 path template；系统必须通过组合基础 URL 和匹配的 path template 构造每个 API Fabric 请求 URI。

#### Scenario: 组装 API Fabric URI
- **WHEN** 匹配的 API Fabric 引用配置了 method `POST`、path template `/orders/{orderId}` 和基础 URL
- **THEN** 下游请求使用 `POST`，并使用基础 URL 与展开后的 path template 组合得到的 URI

### Requirement: CSE 端点保留完整 CSE URI
starter SHALL 为每个 CSE 引用接受 HTTP method 和完整的 `cse://service-name/...` URI template，并将展开后的 URI 传给应用提供的 Spring `RestOperations`，不得使用 WebClient 或改写其 scheme、service name、path。

#### Scenario: CSE URI 保持不变
- **WHEN** 匹配的 CSE 引用展开为 `cse://inventory-service/items/SKU-1`
- **THEN** 下游客户端收到完整 URI，且 `cse` scheme 保持不变

#### Scenario: 应用提供 CSE RestTemplate
- **WHEN** 应用配置了 CSE 端点引用并提供命名为 `cseRestOperations` 的 `RestOperations` Bean
- **THEN** starter 将该客户端直接注入 `CseToolEndpointHandler` 并执行 CSE 请求

#### Scenario: CSE RestTemplate 尚未实现
- **WHEN** 应用配置了 CSE 端点引用但没有提供命名为 `cseRestOperations` 的 `RestOperations` Bean
- **THEN** 应用在发布工具目录之前启动失败，诊断信息明确要求提供该 CSE RestTemplate Bean

### Requirement: 远程端点类别通过统一接口独立替换
starter SHALL 通过公共远程端点处理接口接入不同端点类别，并分别提供 API Fabric 和 CSE 默认实现。应用替换其中一个类别的实现时，MUST NOT 禁用或替换另一个类别的默认实现；scanner 必须汇总全部实现并统一完成工具绑定与跨实现引用校验。

API Fabric 与 CSE 默认实现必须按最终 `Tool.Type` 隔离调用逻辑：API Fabric 实现直接持有 WebClient 且不得依赖 CSE 客户端，CSE 实现直接持有 RestOperations 且不得依赖 API Fabric WebClient；两者之间不得使用额外 provider 包装客户端，共享参数映射组件不得包含按类型选择客户端的运行时分支。

#### Scenario: 只替换 API Fabric 实现
- **WHEN** 应用提供自定义 API Fabric 端点处理实现
- **THEN** starter 使用该自定义实现处理 API Fabric 引用，同时继续使用默认 CSE 实现

#### Scenario: 只替换 CSE 实现
- **WHEN** 应用提供自定义 CSE 端点处理实现
- **THEN** starter 使用该自定义实现处理 CSE 引用，同时继续使用默认 API Fabric 实现

#### Scenario: 不同实现声明重复引用
- **WHEN** 任意两个远程端点处理实现声明同一个引用
- **THEN** 应用在发布工具目录前启动失败，诊断信息指出重复引用及对应实现

#### Scenario: API Fabric 调用实现与 CSE 隔离
- **WHEN** 工具绑定类型为 `API_FABRIC`
- **THEN** `ApiFabricToolEndpointHandler` 使用自身的 WebClient 执行请求，不读取或依赖 CSE RestTemplate

#### Scenario: CSE 调用实现与 API Fabric 隔离
- **WHEN** 工具绑定类型为 `CSE`
- **THEN** `CseToolEndpointHandler` 使用构造时直接注入的 RestOperations 执行请求，不在绑定时再通过 provider 获取客户端，也不读取或依赖 API Fabric WebClient

### Requirement: 按简化规则确定工具参数位置
starter SHALL 从 `path-template` 或 CSE URI template 的占位符自动识别同名 Path 工具参数。Query 和业务 Header 参数必须通过配置显式映射；排除 Path、Query 和业务 Header 参数后，剩余工具参数必须按原参数名自动成为 JSON Body 字段。透传 Header 不属于工具参数，不参与参数位置计算。

#### Scenario: 组装混合请求位置
- **WHEN** 一次工具调用包含 URI 占位符同名参数、已配置的 query 和业务 Header 参数，以及其他参数
- **THEN** URI 占位符参数进入 Path，显式映射参数进入对应位置，其余参数只进入同名 Body 字段

#### Scenario: Path 参数由模板自动识别
- **WHEN** URI template 包含 `{tenantId}`，且工具参数中存在 `tenantId`
- **THEN** 系统使用该工具参数展开 `{tenantId}`，且不要求单独的 Path 配置

#### Scenario: 下游名称与工具参数名称不同
- **WHEN** 工具参数 `dryRun` 映射到 query 名称 `dry_run`
- **THEN** 下游 URI 包含 `dry_run`，且不包含 `dryRun`

#### Scenario: 集合 query 参数重复发送
- **WHEN** 一个映射到 query 的集合包含 `a` 和 `b`
- **THEN** 下游请求在配置的名称下分别包含值为 `a` 和 `b` 的 query 项

### Requirement: JSON Body 字段对 Agent 保持可见
starter SHALL 使用未被 Path、Query 或业务 Header 消耗的工具参数构建 JSON Object Body，不得要求单一包装参数或单独 Body 配置。每个剩余参数必须使用其工具参数原名作为下游 Body 字段名，并保留标量、集合和结构化值。

#### Scenario: 展开的工具参数组成 Body
- **WHEN** `customerId`、`deliveryDate` 和 `lines` 作为独立工具参数暴露，且未被 Path、Query 或业务 Header 消耗
- **THEN** 下游 JSON Body 包含这三个字段，同时工具输入 Schema 继续在根级分别描述这些参数

#### Scenario: 缺失的可选 Body 参数被省略
- **WHEN** `tools/call.arguments` 中不存在一个自动归入 Body 的可选参数
- **THEN** 下游 JSON Body 省略对应字段

#### Scenario: 显式 null Body 参数被保留
- **WHEN** 一个自动归入 Body 的可空参数存在且值为 JSON null
- **THEN** 下游 JSON Body 包含对应字段，且字段值为 JSON null

#### Scenario: 所有工具参数都被其他位置消耗
- **WHEN** 远程工具的全部参数都被 URI 占位符、Query 或业务 Header 消耗
- **THEN** 下游请求不携带请求体

### Requirement: 业务 Header 来自工具参数
starter SHALL 将配置的业务 Header 映射到 MCP 工具参数。这些来源参数必须继续出现在工具输入 Schema 中，并遵循注解参数的必填和可空语义。

#### Scenario: Agent 提供业务 Header
- **WHEN** 工具参数 `bizMode` 映射到业务 Header `X-Biz-Mode`，且其值为 `preview`
- **THEN** 下游请求包含 `X-Biz-Mode: preview`

#### Scenario: 可选业务 Header 缺失
- **WHEN** 一个可选的业务 Header 参数不存在
- **THEN** 下游请求省略该 Header

### Requirement: 非业务入站 Header 默认原样透传
starter SHALL 将当前 MCP `tools/call` HTTP 请求中的非业务 Header 默认复制到下游请求，不要求配置透传允许列表。透传 Header 必须保持其名称及全部值，MUST NOT 出现在工具输入 Schema 或 arguments 中，也不得影响端点选择或 MCP 层业务行为。业务 Header 同名项以及 MCP 协议、内容协商和连接控制 Header 必须排除。

#### Scenario: 入站 Header 被透传
- **WHEN** 当前 MCP 调用携带 `Authorization` 和 `X-Trace-Id` Header，且它们不是业务 Header 或系统排除项
- **THEN** 下游请求携带相同的 Header 名称和值

#### Scenario: 业务 Header 优先于同名入站 Header
- **WHEN** 入站请求包含与已配置业务 Header 同名的 Header，且工具参数提供该业务 Header 的值
- **THEN** 下游请求只使用工具参数生成的业务 Header 值，不透传同名入站值

#### Scenario: 系统 Header 自动排除
- **WHEN** 当前 MCP 调用携带 `Host`、`Content-Length`、`Accept`、`Content-Type`、`Mcp-Session-Id` 或其他系统排除 Header
- **THEN** 下游请求不包含这些入站 Header，且由下游请求构建过程自行设置所需协议 Header

#### Scenario: 透传值不进入审计数据
- **WHEN** 远程工具调用透传安全或链路追踪 Header
- **THEN** 审计 arguments 和 result 不包含透传 Header 的值

#### Scenario: 不同请求之间不泄漏 Header
- **WHEN** 两次 MCP 调用为同一个透传 Header 携带不同的值
- **THEN** 每次下游调用只接收当前 MCP HTTP 请求中的值

### Requirement: 远程响应保持工具契约
starter SHALL 按照注解方法声明的返回类型反序列化成功的下游响应。非成功响应、连接失败、超时、URI 展开失败、请求构造失败或响应转换失败 SHALL 作为 MCP 工具错误返回。

#### Scenario: 转换结构化响应
- **WHEN** 下游端点返回成功 JSON，且内容与注解方法的结构化返回类型兼容
- **THEN** 工具调用结果被转换为该声明类型，并通过现有 MCP 结果适配返回

#### Scenario: 下游失败转换为工具错误
- **WHEN** 下游端点返回非成功状态或远程请求失败
- **THEN** `tools/call` 返回 `isError=true` 的 MCP 结果，且应用保持可用

### Requirement: 发布目录前验证远程绑定
starter SHALL 在向 MCP Server 发布任何远程绑定工具之前，验证全部端点引用、method、URI template、显式 Query/业务 Header 映射和工具关联。

#### Scenario: 引用同时存在于两个端点类别
- **WHEN** 同一个引用同时配置在 API Fabric 和 CSE 下
- **THEN** 应用启动失败，诊断信息明确指出存在歧义的引用

#### Scenario: 配置引用没有对应注解工具
- **WHEN** 一个端点引用未匹配任何注解工具名称
- **THEN** 应用启动失败，诊断信息明确指出未匹配的引用

#### Scenario: 工具参数位置发生冲突
- **WHEN** 一个 Path 参数同时被配置为 Query 或业务 Header，或者同一个工具参数同时配置为 Query 和业务 Header
- **THEN** 应用启动失败，诊断信息明确指出对应引用、参数和冲突位置

#### Scenario: URI 占位符没有同名工具参数
- **WHEN** URI template 中的一个占位符找不到同名工具参数
- **THEN** 应用在发布工具目录之前启动失败，诊断信息明确指出对应引用和占位符

#### Scenario: 配置受限的传输 Header
- **WHEN** 业务 Header 映射尝试设置 `Host`、`Content-Length` 或 `Connection` 等受限传输 Header
- **THEN** 应用在发起任何远程请求之前启动失败

#### Scenario: API Fabric 模板不是绝对路径
- **WHEN** API Fabric `path-template` 未以单个 `/` 开头，或被配置为绝对 URI、scheme-relative URI
- **THEN** 应用在发布工具目录之前启动失败，诊断信息明确指出对应引用和非法 `path-template`

### Requirement: 生产运行时字符串统一使用英文
starter SHALL 对工具审计、注册回滚、审计记录失败、启动校验和远程调用失败等生产运行时字符串使用英文，不得在日志模板、异常消息或断言消息的字符串字面量中写入中文。工具参数、结果和外部异常等动态业务值必须保持原值，不属于语言转换范围。

#### Scenario: 输出工具运行日志
- **WHEN** starter 记录工具审计事件，或框架记录启动校验、注册回滚、审计失败及远程调用异常
- **THEN** starter 自身提供的固定文本、字段名和异常消息只使用英文，不包含中文字符

### Requirement: 函数参数数量保持有限
starter 与消费端全部 Java 源码中显式声明或由 record、Lombok 生成的方法和构造器 SHALL 最多接收 5 个参数。超过该上限的相关值必须按稳定业务职责组合，不得使用无语义的通用参数袋，也不得以删除必要校验或混合远程端点职责规避限制。

#### Scenario: 编译后的函数签名满足上限
- **WHEN** starter 与消费端完成生产和测试源码编译
- **THEN** 每个非 synthetic 方法和构造器的反射参数数量都不超过 5

#### Scenario: 高维远程绑定数据按职责组合
- **WHEN** 远程工具绑定需要同时保存端点、参数映射、请求和返回类型信息
- **THEN** 系统使用端点目标、参数映射和远程请求等有明确职责的不可变值对象组合这些信息，并保持 API Fabric 与 CSE 的调用隔离
