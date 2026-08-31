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
| 应用提供 CSE RestTemplate | `RemoteToolEndpointHandlerTest.preservesCseSchemeAndConvertsGenericResponseWithoutExecutingProxyBody`；消费端完整请求映射测试 |
| CSE RestTemplate 尚未实现 | `RemoteToolEndpointHandlerTest.failsBeforePublishingCseToolWhenRestTemplateIsNotConfigured` |
| 只替换 API Fabric 实现 | `McpFabricAutoConfigurationTest.applicationCanReplaceOnlyApiFabricEndpointHandler` |
| 只替换 CSE 实现 | `McpFabricAutoConfigurationTest.applicationCanReplaceOnlyCseEndpointHandler` |
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

来源与上下文内联检查：`ToolRegistration`、审计事件和共享绑定器统一使用最终解析的
`Tool.Type`；绑定器按该类型选择 API Fabric WebClient 或 CSE RestOperations，`RemoteToolEndpointHandlerTest`
覆盖 `LOCAL`、`API_FABRIC`、`CSE` 三种内置类型。独立的
`ToolOrigin`、重复 `sourceId` 和 `ToolInvocationContext` 均已删除。`ToolInvoker` 仍保持单抽象方法，默认重载
直接接收不可变多值 Header 映射；starter registry 测试和消费端完整请求测试验证 Header 透传、审计隔离及
请求间隔离行为不变。

Remote 包收敛检查：`RemoteRequestHeaders` 同时承担 SDK transport SPI、系统 Header 排除和 CR/LF 校验，替代
原先两个共同维护同一边界的类型；`RemoteToolBindingFactory` 负责 API Fabric/CSE 共享的请求绑定和执行，并在
绑定期预计算业务 Header 名称。API Fabric 与 CSE 默认处理器及公共 SPI 继续独立，确保两类端点能够分别替换。
本轮在相同 `javap -p` 口径下使生产源码从 19 个减少到 18 个、编译类从 33 个减少到 32 个，构造器和方法保持
220 个；CSE RestTemplate 调整删除旧 WebClient provider 并增加同数量的有效 CSE provider，因此生产源码仍为 18 个、
编译类仍为 32 个；按相同口径统计的构造器和方法为 223 个，增量来自 WebClient/RestOperations 两个执行分支及 CSE 客户端启动校验。
remote 包为 6 个生产源码、774 行。新增路径校验方法与删除的重复 Header 类型成员相互抵消，未以
隐藏逻辑换取成员数量下降。

工具行为属性内联检查：`ToolHints` 已删除，scanner 将 `@Tool.readOnly`、`destructive`、`idempotent` 和
`openWorld` 直接写入 `ToolRegistration`。`McpToolRegistryTest.generatedSpecificationInvokesMcpHandler` 验证四个
值原样生成 SDK `ToolAnnotations`；`RemoteToolEndpointHandlerTest.bindsFabricRequestWithAutomaticPathBodyQueryAndHeaderRules`
验证远程 invoker 与类型替换后四个值仍完整保留。

最终门禁：starter `clean verify` 共 50 个测试、消费端 `clean verify` 共 4 个测试全部通过；两侧 JavaDoc 均
生成成功；严格 OpenSpec 校验和 `git diff --check` 通过。starter JAR 只包含收敛后的 7 个 remote 编译类，未
包含 `RemoteHeaderPolicy`、`ServletToolContextExtractor`、`RemoteToolInvocationFactory` 或 `RemoteToolWebClientProvider` 陈旧类，并包含新的
`CseRestTemplateProvider`。
生产字符串扫描同时覆盖 starter 与同级消费端，确认两侧 `src/main/java` 的普通字符串和文本块均不存在中文
字符；中文继续只用于 JavaDoc、注释及交付材料。
