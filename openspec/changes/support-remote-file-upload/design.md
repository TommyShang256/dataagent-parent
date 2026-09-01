## Context

参见 `proposal.md` 的动机和 `specs/remote-file-upload/spec.md` 的行为契约。当前
`RemoteToolInvokerBinder` 在绑定期把 Tool 参数划分为 Path、Query、业务 Header 和自动 JSON Body，
运行期生成一个共享远程请求；API Fabric 与 CSE handler 分别使用自己持有的 WebClient 和
RestOperations 执行该请求。现有共享请求只承载 JSON `ObjectNode`，无法表达文件 part 与普通表单字段。

Tool 输入来自 MCP JSON arguments，不能直接携带本地二进制流；因此文件仍由字符串路径定位。调用进程对
该路径拥有读取权限，BFF 后续负责文件系统沙箱，本 starter 不建立目录白名单。项目继续保持同步 MCP
Server、Tools-only 能力、最多 5 个编译后方法参数以及 API Fabric/CSE 客户端隔离。

## Goals / Non-Goals

**Goals:**

- 用一个字符串 Tool 参数代理一个 `MultipartFile` part，并允许同一请求携带普通表单参数。
- 让 API Fabric 与 CSE 产生语义一致的 multipart 请求，同时保留各自的客户端、URI 和响应处理逻辑。
- 流式读取文件，设置稳定的 part 元数据，并在下游调用前给出可定位的文件与配置错误。
- 不改变未配置上传能力的 JSON 端点，也不改变工具 Schema、Header 透传和审计边界。

**Non-Goals:**

- 不支持一次调用上传多个文件、目录、远程 URL、base64 内容、MCP Resource 或 stdin。
- 不支持 JSON `@RequestPart`、嵌套 multipart、对象表单字段或为普通表单字段重命名。
- 不提供文件根目录白名单、符号链接禁用、病毒扫描、内容嗅探、上传进度、断点续传或异步流式 MCP 调用。
- 不改变下游响应格式，也不为旧配置增加兼容适配层。

## Decisions

### 1. 使用 `files` 映射声明文件 part，首期强制单项

在公共 Endpoint 配置中增加保持插入顺序的 `files` 映射，键是下游 multipart part 名，值是 Tool
字符串参数名：

```yaml
opencode:
  mcp:
    max-upload-file-size: 100MB
    api-fabric:
      endpoints:
        create_table:
          method: POST
          path-template: /v1/createTable
          files:
            dsl: filePath
```

非空 `files` 触发 multipart 模式，当前必须恰好一项。Map 形式与 Query、业务 Header 的
“下游名称: Tool 参数名称”方向一致，不增加只包装 `part-name` 和 `parameter` 的配置类型；将来若明确支持
多文件，可以放宽数量校验而无需迁移配置。空映射继续走现有 JSON Body。

绑定期要求 part 名非空且不区分大小写后没有歧义，来源参数存在且原始 Java 类型为 `String`。文件来源加入
已消费参数集合，并与 Path、Query、业务 Header 做同源冲突校验。同一个 HTTP Header 名和表单字段名仍属于
不同命名空间，不作为冲突。

备选方案是 `multipart.file.part-name/source` 嵌套对象；它为单文件更直观，但会增加纯配置包装类型，也不利于
未来自然扩展。备选方案是通过参数名 `filePath` 自动识别；不采用，因为下游 part 名无法确定，也会让普通路径
参数产生误绑定。

### 2. multipart 模式把剩余简单参数展开为普通表单字段

参数位置计算保持现有顺序：自动 Path、显式 Query、业务 Header、显式文件，最后的剩余参数在 JSON 模式中
成为 JSON 字段，在 multipart 模式中成为同名表单字段。标量使用当前 ObjectMapper 转换为字符串；集合或数组
逐项转换并生成同名重复字段；缺失或 null 不生成 part。

绑定期根据 Java 参数类型只接受字符串、primitive/wrapper、enum、常见 Jackson 可转文本的时间类型，以及这些
标量的数组或具有明确标量泛型的 Collection。对象、Map、原始 Collection、嵌套集合和对象集合直接失败，避免
把 `toString()` 或隐式 JSON 当成下游 `@RequestParam`。文件与普通字段不再产生 JSON Body。

备选方案是把所有剩余参数包装为一个 JSON part；不采用，因为用户给出的接口以 `MultipartFile` 配合普通参数为
主要契约，下游若使用 `@RequestPart` DTO 需要另行设计明确的 JSON part 名。备选方案是每个复杂参数生成 JSON
part；不采用，因为它和传统 `@RequestParam` 的 Content-Type、绑定语义不同，不能安全猜测。

### 3. 共享请求使用类型化 payload，handler 负责各自的 multipart 写出

共享 binder 仍只负责编译参数映射和组装与客户端无关的请求内容。现有远程请求的 JSON body 将收敛为一个包内
payload 值，明确区分无 Body、JSON 和 multipart；multipart payload 保存不可变的普通字段多值映射及单个文件
描述，包括 part 名、Path、文件名和媒体类型。该值不持有 InputStream，也不在 binder 中读取文件内容。

API Fabric handler 遇到 multipart payload 时使用 Spring multipart inserter 和文件 Resource 写入 WebClient
请求；CSE handler 构造 multipart MultiValueMap/HttpEntity 交给应用提供的 RestOperations。两者都让 Spring
消息写出器创建 boundary，不手工拼接 multipart 字节，也不把文件整体读入内存。FileSystemResource 仅在实际
请求期间打开流，消息写出器完成或失败时关闭流；请求模型本身不拥有需要显式关闭的句柄。

这样继续满足“handler 直接持有并使用自己的客户端”，同时避免在两个 handler 中重复参数划分和文件校验。
备选方案是让 binder 直接创建客户端专用 BodyInserter 或 HttpEntity；不采用，因为会把 WebClient 或
RestOperations 表示泄漏到共享绑定层。备选方案是统一两个 handler；不采用，因为违反当前客户端隔离约束。

### 4. 运行期以 Path/Resource 校验并传输文件

调用时先读取 filePath 原始字符串并执行：非空、`Path.of` 可解析、`Files.exists`、`Files.isRegularFile`、
`Files.isReadable` 和 `Files.size` 上限校验。检查默认跟随符号链接，不做规范路径根目录判断；这与用户指定由
BFF 承担沙箱一致。配置新增全局 `max-upload-file-size`，类型为 DataSize，默认 `100MB`，启动期要求为正数。

文件名使用 `path.getFileName()`；媒体类型先使用 `Files.probeContentType`，空值或非法值回退
`application/octet-stream`。校验完成后才创建远程请求，错误消息使用英文并至少包含端点引用和原始路径。
文件大小在校验与读取之间变化属于普通文件系统竞争：Spring 实际读取当前内容，不缓存校验时快照；下游仍受
自身请求限制保护。

备选方案是读取 byte[] 后上传；不采用，因为会按文件大小占用堆并削弱大文件可靠性。备选方案是完全不设 starter
大小限制；不采用，因为本地文件不受 MCP 入站 `max-request-size` 约束，必须存在独立且可配置的保护。

### 5. Schema、审计、错误和兼容性边界保持稳定

Schema generator 不引入文件格式，`filePath` 仍是字符串。Registry 审计继续记录 arguments，因此会记录路径但
不会触碰文件内容；multipart payload 与 Resource 不进入审计事件。Path、Query、业务 Header、透传 Header、
超时、成功响应转换和错误响应映射复用现有逻辑。

未配置 `files` 的所有端点保持当前 JSON 行为。配置了文件的端点不允许再产生 JSON Body，因此这是显式选择而非
按 HTTP method 或文件名猜测。API Fabric/CSE handler 的公共 SPI 不增加文件专用方法，第三方 handler 是否支持
multipart 由其是否接受共享 payload 决定；当前项目只承诺两个内置 handler。

### 6. 使用 JaCoCo 与测试命名策略形成持续门禁

starter 在 `verify` 阶段执行 JaCoCo bundle 级检查，指令覆盖率和分支覆盖率最低值均设为 `0.91`。选择两个指标
而不只使用行覆盖率，是为了同时约束主要执行路径和文件校验、参数绑定、客户端分流中的条件分支。覆盖率统计只包含
生产代码，测试夹具不进入分母；门禁是构建的一部分，后续代码降低覆盖率时直接失败。

主工程增加源码级测试命名策略检查，扫描主工程与同级消费端测试源码，要求每个 JUnit `@Test`、
`@ParameterizedTest`、`@RepeatedTest` 或 `@TestFactory` 方法都显式使用中文 `@DisplayName`。测试类和
`@TestConfiguration` 不作为测试用例。源码级检查比依赖测试报告中的动态名称更早发现遗漏，并能够报告具体文件。

备选方案是只在本次补充 `@DisplayName` 而不增加策略测试；不采用，因为后续新增用例仍可能退化。备选方案是仅统计
文件上传包覆盖率；不采用，因为用户要求当前项目的高覆盖率，局部统计会隐藏配置、扫描和注册路径的缺口。

## Risks / Trade-offs

- [任意 filePath 可读取应用权限范围内的文件] → 按用户决定不在 starter 中增加白名单；文档明确该能力只应部署在具有 BFF 沙箱或等价隔离的环境。
- [文件在校验后、上传前被替换或增大] → 不持有长期句柄或加载快照；记录竞争边界，并依赖配置上限的预检查及下游限制。
- [不同 HTTP 客户端生成的 boundary 和 part Header 排序不同] → 测试比较 part 语义而非原始字节，要求名称、文件名、媒体类型、字段和值一致。
- [普通参数类型过宽会导致下游绑定不确定] → 绑定期只接受明确标量或标量集合，复杂对象留给未来 JSON part 能力。
- [默认 100MB 对个别接口过小或过大] → 提供统一 DataSize 配置，部署可按环境调整，并在启动期拒绝非正值。
- [文件名或路径可能出现在下游日志和现有 arguments 审计] → 文件内容永不进入审计；路径脱敏继续由应用提供的 ToolAuditLogger 负责。

## Migration Plan

1. 增加 `files` 和 `max-upload-file-size` 配置、共享 multipart payload 与双 handler 写出能力；不修改现有端点配置。
2. 在消费端增加代表性的 `String filePath` Tool 参数和 API Fabric/CSE 上传端点，通过捕获请求确认 multipart 语义。
3. 部署前确保 BFF 沙箱或应用进程文件权限符合预期，再逐个端点增加 `files` 映射并重启应用。
4. 回滚时删除端点 `files` 映射；端点恢复现有 JSON Body 规则。代码回滚不需要数据迁移。

## Open Questions

无。JSON `@RequestPart`、多文件与更强文件系统隔离需要独立需求后再扩展。
