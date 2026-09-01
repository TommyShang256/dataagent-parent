## Why

目前注解工具只能执行本地 Java 方法体。starter 需要提供配置驱动的绑定能力，将注解工具连接到 API Fabric 或 CSE 端点，使工具目录继续向 Agent Loop 暴露信息完整的 Schema，同时让 `tools/call` 真正访问下游服务。

## What Changes

- 使用 `@Tool.name` 作为端点引用，在启动时将注解工具与一个 API Fabric 或 CSE 端点匹配。
- 增加 API Fabric 配置：所有接口共享一个 `base-url`，每个引用分别配置 HTTP method 和 `path-template`。
- 增加 CSE 配置：每个引用分别配置完整的 `cse://service-name/...` URI 模板和 HTTP method；交给应用提供的 `RestOperations` 时保持 URI 不变。
- 将远程端点类别抽象为统一的公共处理接口，并分别提供可独立替换的 API Fabric 与 CSE 默认实现；scanner 统一完成跨实现的引用校验和工具绑定。
- 从 `path-template` 或 CSE URI template 的同名占位符自动识别 Path 参数；Query 和业务 Header 继续显式配置，排除这些参数后，将剩余工具参数按原名自动组装为展开的 JSON Body。
- 配置中只声明来自工具参数的业务 Header；当前 MCP HTTP 请求中的其他 Header 除业务同名项和系统排除项外，默认原样透传到下游。
- 将请求级透传 Header 通过 MCP 调用处理器传递到远程调用，同时不把它们加入工具 Schema、arguments 或审计值，并阻止 MCP 协议及连接控制 Header 向下游传播。
- 在发布固定的启动期工具目录之前验证全部远程绑定；存在歧义、缺失或不一致映射时启动失败。
- 使用注解方法声明的返回类型转换成功的下游响应，并将下游失败呈现为 MCP 工具错误。
- 在消费端测试模块中覆盖 API Fabric 和 CSE 路由，包括请求构造及两类 Header 行为。
- 删除没有实际消费场景的 `RemoteToolClient` 编程式注册路径，工具统一由 `@Tool` 声明。
- 将最终绑定类型收敛为 `Tool.Type`，删除同时保存重复来源标识的 `ToolOrigin`；将只包装请求 Header 的 `ToolInvocationContext` 内联为不可变多值 Header 映射。
- 将只复制 `@Tool` 四个行为属性的 `ToolHints` 内联到 `ToolRegistration`，减少重复模型和对象转换。
- 合并 Servlet Header 提取与远程 Header 策略，收敛共享绑定工厂的命名和内部实现，同时保持 API Fabric/CSE handler 的独立扩展边界。
- API Fabric 与 CSE 的调用逻辑分别明确收敛到 `ApiFabricToolEndpointHandler` 和 `CseToolEndpointHandler`；前者直接注入 WebClient，后者直接注入命名为 `cseRestOperations` 的 `RestOperations`。共享绑定器只负责参数映射，不再按 `Tool.Type` 隐式选择客户端，也不保留额外的 CSE provider 包装层。
- 统一使用英文生产运行时字符串，覆盖日志模板、异常消息和断言消息，禁止中文字符串经框架异常记录进入日志。
- 将 starter 与消费端全部显式或生成的方法、构造器参数数量限制为最多 5 个，使用具有业务内聚性的值对象收敛高维注册、审计和远程调用契约。
- 将 multipart 文件上传记录为后续待办；本次变更只支持 JSON 请求体。

## Capabilities

### New Capabilities

- `remote-tool-routing`：定义注解工具的配置、启动绑定、请求参数位置、Header 透传、API Fabric WebClient/CSE RestOperations 调用、响应转换和校验行为。

### Modified Capabilities

无。

## Impact

- 影响工具扫描、标准化调用、MCP transport context 提取、registry 调用处理、自动配置、配置属性与元数据、文档，以及同级的 `dataagent-mcp-test` 消费端应用。
- 增加 API Fabric 所需的 Spring WebFlux 客户端 API，同时保留现有基于 Servlet 的 MCP Server。
- 引入可配置的 API Fabric/CSE 端点模型、可扩展的端点处理 SPI，以及分别直接注入两个默认 handler 的 API Fabric WebClient 和 CSE RestOperations 客户端。
- 不增加 Resources、Prompts、运行时目录修改、multipart 上传或固定 Header 配置。
