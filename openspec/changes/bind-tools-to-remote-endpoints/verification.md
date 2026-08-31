# 验证映射

本文件将 `remote-tool-routing` 规范场景映射到已通过的自动化测试或交付文档。

| 规范场景 | 验证依据 |
| --- | --- |
| API Fabric 引用绑定注解工具 | `RemoteToolEndpointBinderTest.bindsFabricRequestWithAutomaticPathBodyQueryAndHeaderRules`；消费端 `invokesLocalAndRemoteToolsWithCompleteRequestMapping` |
| CSE 引用绑定注解工具 | `RemoteToolEndpointBinderTest.preservesCseSchemeAndConvertsGenericResponseWithoutExecutingProxyBody`；消费端固定目录测试 |
| 未绑定的注解工具保持本地调用 | `RemoteToolEndpointBinderTest.leavesUnmatchedAnnotationToolLocalAndMapsDownstreamErrors` |
| 组装 API Fabric URI | starter 与消费端完整请求映射测试 |
| CSE URI 保持不变 | starter 与消费端 CSE 捕获请求断言 |
| 组装混合请求位置 | `bindsFabricRequestWithAutomaticPathBodyQueryAndHeaderRules` |
| Path 参数由模板自动识别 | starter Path 编码、缺失参数和无 Path 配置断言 |
| 下游名称与工具参数名称不同 | starter `tag: tags` 与消费端 `dry_run: dryRun` 请求断言 |
| 集合 Query 参数重复发送 | starter 和消费端的两个 `tag` 值断言 |
| 展开的工具参数组成 Body | starter Body JSON 断言；消费端根 Schema 与 Body 捕获断言 |
| 缺失的可选 Body 参数被省略 | `RemoteToolEndpointBinderTest.omitsMissingOptionalBodyButKeepsExplicitNullAndSendsNoBodyWhenAllConsumed` |
| 显式 null Body 参数被保留 | 同上 |
| 所有工具参数都被其他位置消耗 | 同上，无请求体断言 |
| Agent 提供业务 Header | starter 与消费端 `X-Biz-Mode` 断言 |
| 可选业务 Header 缺失 | starter 的 null/缺失值省略逻辑及聚焦请求捕获测试 |
| 入站 Header 被透传 | starter多值 `Authorization` 与消费端 `Authorization`、`X-Trace-Id` 断言 |
| 业务 Header 优先于同名入站 Header | starter和消费端不区分大小写覆盖断言 |
| 系统 Header 自动排除 | `ServletToolContextExtractorTest` 与两级请求捕获的 `Host` 过滤断言 |
| 透传值不进入审计数据 | `McpToolRegistryTest.passesTransportContextThroughHandlerAndAuditWrapperWithoutAuditingHeaders` |
| 不同请求之间不泄漏 Header | 同一测试连续使用 `first`、`second` 两个 context 的隔离断言 |
| 转换结构化响应 | starter record/泛型测试及消费端 `Order`、`Reservation` 转换断言 |
| 下游失败转换为工具错误 | starter非 2xx、connector、超时、URI、转换失败测试；消费端 502 测试；registry MCP error adapter 测试 |
| 引用同时存在于两个端点类别 | `RemoteToolEndpointBinderTest.validatesWholeCatalogBeforeReturningAnyRegistration` |
| 配置引用没有对应注解工具 | 同上 |
| 工具参数位置发生冲突 | 同上，覆盖 Path/Query 与 Query/业务 Header 冲突 |
| URI 占位符没有同名工具参数 | 同上 |
| 配置受限的传输 Header | 同上，覆盖 `Content-Length` |

额外交付检查：`README.md` 说明同步执行、普通 Header 默认透传、固定系统排除集合、CSE connector 责任、
敏感业务 Header 审计建议，以及当前仅支持 JSON Body；`multipart/form-data` 文件上传及其大小限制、流式传输和
资源清理被明确记录为后续待办。
