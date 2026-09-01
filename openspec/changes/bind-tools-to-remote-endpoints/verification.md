# 验证映射

本文件将 `remote-tool-routing` 规范场景映射到已通过的自动化测试或交付文档。

| 规范场景 | 验证依据 |
| --- | --- |
| API Fabric 引用绑定注解工具 | `RemoteToolEndpointHandlerTest.bindsFabricRequestWithAutomaticPathBodyQueryAndHeaderRules`；消费端 `invokesLocalAndRemoteToolsWithCompleteRequestMapping` |
| CSE 引用绑定注解工具 | `RemoteToolEndpointHandlerTest.preservesCseSchemeAndConvertsGenericResponseWithoutExecutingProxyBody`；消费端固定目录测试 |
| 未绑定的注解工具保持本地调用 | `RemoteToolEndpointHandlerTest.leavesUnmatchedAnnotationToolLocalAndMapsDownstreamErrors` |
| 组装 API Fabric URI | starter 与消费端完整请求映射测试 |
| API Fabric 模板不是绝对路径 | `RemoteToolEndpointHandlerTest.validatesWholeCatalogBeforeReturningAnyRegistration`，覆盖绝对 URL、相对路径和 scheme-relative 路径 |
| 输出工具运行日志 | `LoggingLanguageTest` 扫描 starter 与消费端全部生产字符串字面量并捕获实际审计日志，确认日志模板、异常及断言消息不包含中文字符 |
| CSE URI 保持不变 | starter 与消费端 CSE 捕获请求断言 |
| 应用提供 CSE RestTemplate | `McpFabricAutoConfigurationTest.applicationCanProvideCseRestOperations`；`RemoteToolEndpointHandlerTest.preservesCseSchemeAndConvertsGenericResponseWithoutExecutingProxyBody`；消费端完整请求映射测试 |
| CSE RestTemplate 尚未实现 | `McpFabricAutoConfigurationTest.failsBeforePublishingCseToolWhenNamedRestOperationsIsMissing`；`RemoteToolEndpointHandlerTest.failsBeforePublishingCseToolWhenRestTemplateIsNotConfigured` |
| 只替换 API Fabric 实现 | `McpFabricAutoConfigurationTest.applicationCanReplaceOnlyApiFabricEndpointHandler` |
| 只替换 CSE 实现 | `McpFabricAutoConfigurationTest.applicationCanReplaceOnlyCseEndpointHandler` |
| API Fabric 与 CSE 调用依赖隔离 | `RemoteToolEndpointHandlerTest.isolatesApiFabricAndCseClientDependencies`；两个完整请求捕获测试分别验证 WebClient 与 RestOperations 调用 |
| 不同实现声明重复引用 | `McpToolScannerEndpointHandlerTest.rejectsDuplicateReferenceAcrossHandlers` |
| 组装混合请求位置 | `bindsFabricRequestWithAutomaticPathBodyQueryAndHeaderRules` |
| Path 参数由模板自动识别 | starter Path 编码、缺失参数和无 Path 配置断言 |
| 下游名称与工具参数名称不同 | starter `tag: tags` 与消费端 `dry_run: dryRun` 请求断言 |
| 集合 Query 参数重复发送 | starter 和消费端的两个 `tag` 值断言 |
| 展开的工具参数组成 Body | starter Body JSON 断言；消费端根 Schema 与 Body 捕获断言 |
| 缺失的可选 Body 参数被省略 | `RemoteToolEndpointHandlerTest.omitsMissingOptionalBodyButKeepsExplicitNullAndSendsNoBodyWhenAllConsumed` |
| 显式 null Body 参数被保留 | 同上 |
| 所有工具参数都被其他位置消耗 | 同上，无请求体断言 |
| Agent 提供业务 Header | starter 与消费端 `X-Biz-Mode` 断言 |
| 可选业务 Header 缺失 | starter 的 null/缺失值省略逻辑及聚焦请求捕获测试 |
| 入站 Header 被透传 | starter多值 `Authorization` 与消费端 `Authorization`、`X-Trace-Id` 断言 |
| 业务 Header 优先于同名入站 Header | starter和消费端不区分大小写覆盖断言 |
| 系统 Header 自动排除 | `RemoteRequestHeadersTest` 与两级请求捕获的 `Host` 过滤断言 |
| 透传值不进入审计数据 | `McpToolRegistryTest.passesTransportContextThroughHandlerAndAuditWrapperWithoutAuditingHeaders` |
| 不同请求之间不泄漏 Header | 同一测试连续使用 `first`、`second` 两组 Header 映射的隔离断言 |
| 转换结构化响应 | starter record/泛型测试及消费端 `Order`、`Reservation` 转换断言 |
| 下游失败转换为工具错误 | starter非 2xx、connector、超时、URI、转换失败测试；消费端 502 测试；registry MCP error adapter 测试 |
| 引用同时存在于两个端点类别 | `RemoteToolEndpointHandlerTest.validatesWholeCatalogBeforeReturningAnyRegistration` |
| 配置引用没有对应注解工具 | 同上 |
| 工具参数位置发生冲突 | 同上，覆盖 Path/Query 与 Query/业务 Header 冲突 |
| URI 占位符没有同名工具参数 | 同上 |
| 配置受限的传输 Header | 同上，覆盖 `Content-Length` |

额外交付检查：`README.md` 说明同步执行、普通 Header 默认透传、固定系统排除集合、CSE RestTemplate 责任、
敏感业务 Header 审计建议，以及当前仅支持 JSON Body；`multipart/form-data` 文件上传及其大小限制、流式传输和
资源清理被明确记录为后续待办。

远程端点 SPI 的额外交付检查：`McpFabricAutoConfigurationTest.createsIndependentDefaultEndpointHandlers` 验证两个
默认实现同时存在；`applicationCanAddAnotherEndpointHandler` 验证扩展实现会加入 scanner 的端点绑定；
`McpToolScannerEndpointHandlerTest` 同时覆盖本地 fallback、未知 ref 和跨实现重复 ref。

结构精简检查：全部重构使生产 Java 源文件从 26 个减少到 19 个，编译后类声明从 44 个减少到 33 个。
来源与上下文内联阶段使用同一 Git 暂存快照和相同 `javap -p` 规则复算，源文件从 22 个减少到 20 个、编译类
从 36 个减少到 34 个、已声明构造器和方法从 237 个减少到 225 个。本轮行为属性内联继续使源文件从 20 个
减少到 19 个、编译类从 34 个减少到 33 个、构造器和方法从 225 个减少到 220 个。计数包含 Lombok 生成方法，
因而反映实际字节码 API 与内部成员的变化。

工具声明路径检查：已删除 `RemoteToolClient` 及 scanner 中的编程式工具注册分支。
`McpFabricAutoConfigurationTest.registersAnnotatedTools` 和 `invokesAnnotatedToolsAndAuditsOperations`
使用四个注解工具验证固定目录、本地调用及审计行为。API Fabric/CSE 远程绑定继续由
`RemoteToolEndpointHandlerTest` 和消费端集成测试覆盖。

来源与上下文内联检查：`ToolRegistration` 和审计事件统一使用最终解析的 `Tool.Type`；scanner 按 ref 选择唯一
handler，由 handler 设置最终类型。共享绑定器不按类型选择客户端，`RemoteToolEndpointHandlerTest` 覆盖
`LOCAL`、`API_FABRIC`、`CSE` 三种内置类型。独立的
`ToolOrigin`、重复 `sourceId` 和 `ToolInvocationContext` 均已删除。`ToolInvoker` 仍保持单抽象方法，默认重载
直接接收不可变多值 Header 映射；starter registry 测试和消费端完整请求测试验证 Header 透传、审计隔离及
请求间隔离行为不变。

Remote 包收敛检查：`RemoteRequestHeaders` 同时承担 SDK transport SPI、系统 Header 排除和 CR/LF 校验，替代
原先两个共同维护同一边界的类型；`RemoteToolInvokerBinder` 只负责 API Fabric/CSE 共享的请求参数映射，并在
绑定期预计算业务 Header 名称。API Fabric 与 CSE 默认处理器分别拥有 WebClient 和 RestOperations 调用逻辑及
响应处理，且不持有对方客户端依赖，确保两类端点能够分别替换。
本轮在相同 `javap -p` 口径下使生产源码从 19 个减少到 18 个、编译类从 33 个减少到 32 个，构造器和方法保持
220 个；CSE RestTemplate 调整删除旧 WebClient provider 并增加同数量的有效 CSE provider，因此生产源码仍为 18 个、
编译类仍为 32 个；按相同口径统计的构造器和方法为 223 个，增量来自 WebClient/RestOperations 两个执行分支及 CSE 客户端启动校验。
remote 包为 6 个生产源码、774 行。新增路径校验方法与删除的重复 Header 类型成员相互抵消，未以
隐藏逻辑换取成员数量下降。

本轮调用隔离后，生产源码仍为 18 个，remote 包仍为 6 个生产源码；`RemoteToolInvokerBinder` 不再引用
`Tool.Type`、WebClient、RestOperations 或 CSE provider。为在不扩大公共 SPI 的前提下把已映射请求交给当前
handler，工厂只增加一个包内嵌套函数接口，因此编译类为 33 个；按相同 `javap -p` 口径统计构造器和方法为
223 个，remote 包为 797 行。两个 handler 各自增加的调用代码来自原共享绑定器，没有新增顶层生产类型。

CSE 客户端直接注入后，`CseRestTemplateProvider` 及其默认占位 Bean 已删除；两个 handler 现在分别直接持有
WebClient 和 RestOperations。生产源码由 18 个减少为 17 个，编译类由 33 个减少为 32 个，按相同
`javap -p` 口径统计构造器和方法由 223 个减少为 220 个；remote 包由 6 个生产源码减少为 5 个，共 780 行。
自动配置只使用按名称限定的 `ObjectProvider<RestOperations>` 处理可选装配，不形成公共类型或远程调用中间层。

工具行为属性内联检查：`ToolHints` 已删除，scanner 将 `@Tool.readOnly`、`destructive`、`idempotent` 和
`openWorld` 直接写入 `ToolRegistration`。`McpToolRegistryTest.generatedSpecificationInvokesMcpHandler` 验证四个
值原样生成 SDK `ToolAnnotations`；`RemoteToolEndpointHandlerTest.bindsFabricRequestWithAutomaticPathBodyQueryAndHeaderRules`
验证远程 invoker 与类型替换后四个值仍完整保留。

最终门禁：starter `clean verify` 共 52 个测试、消费端 `clean verify` 共 4 个测试全部通过；两侧 JavaDoc 均
生成成功；严格 OpenSpec 校验和 `git diff --check` 通过。starter JAR 只包含收敛后的 7 个 remote 编译类，未
包含 `RemoteHeaderPolicy`、`ServletToolContextExtractor`、`RemoteToolInvocationFactory`、
`RemoteToolWebClientProvider` 或 `CseRestTemplateProvider` 陈旧类。
生产字符串扫描同时覆盖 starter 与同级消费端，确认两侧 `src/main/java` 的普通字符串和文本块均不存在中文
字符；中文继续只用于 JavaDoc、注释及交付材料。

函数参数上限检查：starter 与消费端分别增加编译字节码反射测试，扫描 `target/classes` 和
`target/test-classes` 中全部非 synthetic 方法与构造器；测试同时覆盖显式源码、record 隐式构造器及 Lombok
生成构造器，确认最大参数数量不超过 5。`ToolRegistration` 使用定义与行为值归组注册数据，`ToolAuditEvent`
使用目标与详情值归组审计数据；`RemoteToolInvokerBinder` 使用绑定目标、调用端点、参数映射和单次远程请求值，
保持 binder 不持有客户端且两个 handler 仍分别执行 WebClient 和 RestOperations 请求。

消费端 `createOrder` 收敛为 5 个根级工具参数，继续覆盖自动 Path、集合 Query、业务 Header 以及
`customerId`、`lines` 两个展开 Body 字段；CSE 工具继续覆盖标量 Query，API Fabric 返回值继续覆盖
`deliveryDate` 日期转换。starter 53 个测试和消费端 5 个测试全部通过，其中各包含 1 个参数上限策略测试。

结构检查使用与历史一致的 `javap -p` 口径：生产源码保持 17 个；为显式建模原先平铺的高维数据增加 8 个嵌套
不可变类型，编译类由 32 个变为 40 个，构造器和方法由 220 个变为 246 个，没有增加顶层生产类型。
remote 包保持 5 个生产源码、785 行和 11 个编译类。starter JAR 共 40 个 class，只包含
`RemoteToolInvokerBinder` 及其嵌套计划类型，不包含旧 `RemoteToolBindingFactory`、`RemoteToolBinder`、
`CseRestTemplateProvider` 或其他陈旧类。

## opencode 真实 MCP Client 调测记录

### 调测目标与拓扑

本轮不使用直接调用 registry 或捕获式 WebClient 替身，而是验证完整网络链路：

```text
opencode MCPClient
  -> initialize
  -> tools/list
  -> tools/call(create_order)
  -> dataagent-mcp /rest/mcp
  -> ApiFabricToolEndpointHandler WebClient
  -> JDK HttpServer API Fabric mock
  -> JSON response
  -> MCP CallToolResult
```

自动化入口为
`src/test/java/ai/opencode/mcp/ApiFabricOpenCodeE2eTest.java`。测试启动两个随机本地端口，
避免依赖固定端口或已有进程：一个运行 Spring Servlet MCP Server，另一个运行 JDK
`HttpServer` API Fabric mock。

### 客户端与环境

- 日期：2026-09-01，时区 `Asia/Shanghai`。
- dataagent-mcp：当前仓库源码，MCP 端点 `/rest/mcp`。
- opencode：`/Users/tommy/projects/opencode`，分支 `v2`；测试直接使用
  `packages/core/src/mcp/client.ts` 导出的 `MCPClient`。
- opencode 远程 MCP 配置：`ConfigMCP.Remote`，URL 指向当次启动的
  `http://127.0.0.1:<random>/rest/mcp`，`oauth=false`。
- 客户端 Header：`X-Trace-Id: opencode-e2e`，用于验证 MCP HTTP 入站 Header 能透传到 API Fabric。
- Java：OpenJDK `26.0.2.1`，Maven 使用 `release 21` 编译。
- Bun：使用当前 shell 中可执行的 `bun`。
- Maven：系统 `PATH` 中没有 `mvn`；本次将 Apache Maven `3.9.11` 解压到
  `/tmp/dataagent-maven.USrwT0`，不修改系统安装或仓库构建配置。

opencode 客户端的关键配置与标准请求为：

```typescript
new ConfigMCP.Remote({
  type: "remote",
  url: process.env.MCP_E2E_URL,
  oauth: false,
  headers: { "X-Trace-Id": "opencode-e2e" },
})

connection.tools()
connection.callTool({
  name: "create_order",
  args: {
    orderId: "O-1",
    verbose: true,
    bizMode: "preview",
    customerId: "C-1",
  },
})
```

`MCPClient.connect` 在返回 connection 前执行 MCP initialize，`connection.tools()` 发起
`tools/list`，`connection.callTool()` 发起 `tools/call`。Server 实际日志确认客户端名为
`opencode`，协议版本为 `2025-11-25`。测试环境将 Spring shutdown phase 超时设为 1 秒，
避免 Streamable HTTP 长连接在 context 关闭时消耗默认 30 秒；该设置不进入生产配置。

### API Fabric mock 契约

MCP Server 中的 `create_order` 注解方法保留一个主动失败的方法体，用于证明远程绑定后没有
执行本地 Java 方法。端点配置将 `orderId` 用于 Path，`verbose` 用于 Query，`bizMode`
用于业务 Header，剩余 `customerId` 自动进入 JSON Body。

实际被断言的下游请求为：

```http
POST /api/orders/O-1?verbose=true
X-Trace-Id: opencode-e2e
X-Biz-Mode: preview
Content-Type: application/json

{"customerId":"C-1"}
```

mock 返回：

```http
HTTP/1.1 200 OK
Content-Type: application/json

{"id":"O-1","status":"created"}
```

opencode 观察到的工具目录包含 `create_order`，调用结果为 `isError=false`，文本内容反序列化后为：

```json
{"id":"O-1","status":"created"}
```

### 执行命令与结果

首次尝试使用 `mvn -Dtest=ApiFabricOpenCodeE2eTest test`，shell 返回 `command not found: mvn`。
确认项目没有 Maven Wrapper 后，改用临时 Maven：

```shell
/tmp/dataagent-maven.USrwT0/apache-maven-3.9.11/bin/mvn \
  -Dtest=ApiFabricOpenCodeE2eTest test
```

实际结果：

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 33.259 s
Finished at: 2026-09-01T10:21:24+08:00
```

随后首次执行全量 `clean verify` 时，端到端测试仍然通过，但后续
`LoggingLanguageTest.auditLoggerFormatsEnglishTemplate` 失败。根因是端到端测试为减少输出设置了
`logging.level.root=OFF`，Spring context 关闭后没有还原全局 Logback 根级别，使下一个测试无法捕获
审计日志。修正方式是删除该全局日志配置，保持测试之间的日志状态隔离；不改动生产代码。

最终门禁结果：

```text
mvn clean verify
Tests run: 54, Failures: 0, Errors: 0, Skipped: 0
ApiFabricOpenCodeE2eTest: 2.269 s
BUILD SUCCESS
Total time: 4.832 s
Finished at: 2026-09-01T10:26:33+08:00

mvn javadoc:javadoc
BUILD SUCCESS
Total time: 1.237 s

openspec validate bind-tools-to-remote-endpoints --strict
Change 'bind-tools-to-remote-endpoints' is valid

git diff --check
passed
```

JavaDoc 生成保留了项目已有的 10 个“默认构造器没有注释”警告，没有新增 JavaDoc 错误，
命令成功退出。JAR 内容检查确认新增端到端测试只存在于 `target/test-classes`，未进入
`dataagent-mcp-0.1.0-SNAPSHOT.jar`。

测试在同级 `../opencode` 不存在或 Bun 不可用时使用 JUnit assumption 明确跳过，以保持独立
starter 构建可运行；本次实际执行环境中两项依赖均存在，因此结果为真实执行而非跳过。

### 结论

opencode V2 能将当前 dataagent-mcp 配置为远程 MCP Server，完成标准 initialize、
`tools/list` 和 `tools/call`。`tools/call` 能经由当前 API Fabric handler 发起真实 HTTP
请求，且 Path、Query、业务 Header、透传 Header、JSON Body 和返回类型转换均与当前设计一致。

## Transport Header 职责收敛验证

### 重构结果

原 `McpToolRegistry.headers` 中对 transport context key、未类型化值、Header 名称与值类型的处理，
已下沉为 `RemoteRequestHeaders.from`。Registry 只负责从 MCP exchange 取得 transport context 并消费
类型化的不可变 `Map<String, List<String>>`，不再知道 Header 在 context 中的存储约定。

读取方法对空 context、缺少目标值和非 Map 值返回空映射；对 Map 中非字符串名称、非 List 值及
List 中非字符串项进行过滤。返回结果同时复制外层 Map 和内层 List，源集合后续修改不会影响调用，
调用方也不能修改返回集合。

### 测试与构建结果

```text
mvn -Dtest=RemoteRequestHeadersTest,McpToolRegistryTest test
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 2.267 s
Finished at: 2026-09-01T11:00:06+08:00

mvn clean verify
Tests run: 56, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 5.275 s
Finished at: 2026-09-01T11:00:54+08:00
```

全量构建再次执行了真实 opencode MCP Client 端到端测试，initialize、`tools/list`、`tools/call`
及 API Fabric mock 请求均成功，证明职责下沉没有改变远程工具调用行为。

最终门禁结果：

```text
mvn javadoc:javadoc
BUILD SUCCESS
Total time: 1.486 s

openspec validate bind-tools-to-remote-endpoints --strict
Change 'bind-tools-to-remote-endpoints' is valid

openspec instructions apply --change bind-tools-to-remote-endpoints
Progress: 59/59 complete

git diff --check
passed
```

JavaDoc 保留既有 10 个默认构造器警告，没有新增错误。结构口径保持 17 个生产 Java 源文件、
40 个生产 class；本次未新增类型。`javap -p` 显示 `McpToolRegistry` 删除一个私有 `headers`
方法，`RemoteRequestHeaders` 增加一个公开静态 `from` 方法，两个类合计的构造器与方法数量不变。
JAR 检查只发现两个生产类及 registry 嵌套类，没有包含 `ApiFabricOpenCodeE2eTest` 等测试类。
