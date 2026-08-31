## Why

目前，除非应用自行提供完整的 `RemoteToolClient`，否则注解工具只能执行本地 Java 方法体。starter 需要提供配置驱动的绑定能力，将注解工具连接到 API Fabric 或 CSE 端点，使工具目录继续向 Agent Loop 暴露信息完整的 Schema，同时让 `tools/call` 真正访问下游服务。

## What Changes

- 使用 `@Tool.name` 作为端点引用，在启动时将注解工具与一个 API Fabric 或 CSE 端点匹配。
- 增加 API Fabric 配置：所有接口共享一个 `base-url`，每个引用分别配置 HTTP method 和 `path-template`。
- 增加 CSE 配置：每个引用分别配置完整的 `cse://service-name/...` URI 模板和 HTTP method；交给 `WebClient` 时保持 URI 不变。
- 从 `path-template` 或 CSE URI template 的同名占位符自动识别 Path 参数；Query 和业务 Header 继续显式配置，排除这些参数后，将剩余工具参数按原名自动组装为展开的 JSON Body。
- 配置中只声明来自工具参数的业务 Header；当前 MCP HTTP 请求中的其他 Header 除业务同名项和系统排除项外，默认原样透传到下游。
- 将请求级透传 Header 通过 MCP 调用处理器传递到远程调用，同时不把它们加入工具 Schema、arguments 或审计值，并阻止 MCP 协议及连接控制 Header 向下游传播。
- 在发布固定的启动期工具目录之前验证全部远程绑定；存在歧义、缺失或不一致映射时启动失败。
- 使用注解方法声明的返回类型转换成功的下游响应，并将下游失败呈现为 MCP 工具错误。
- 在消费端测试模块中覆盖 API Fabric 和 CSE 路由，包括请求构造及两类 Header 行为。
- 将 multipart 文件上传记录为后续待办；本次变更只支持 JSON 请求体。

## Capabilities

### New Capabilities

- `remote-tool-routing`：定义注解工具的配置、启动绑定、请求参数位置、Header 透传、`WebClient` 调用、响应转换和校验行为。

### Modified Capabilities

无。

## Impact

- 影响工具扫描、标准化调用、MCP transport context 提取、registry 调用处理、自动配置、配置属性与元数据、文档，以及同级的 `dataagent-mcp-test` 消费端应用。
- 增加 `WebClient` 所需的 Spring WebFlux 客户端 API，同时保留现有基于 Servlet 的 MCP Server。
- 引入可配置的 API Fabric/CSE 端点模型和可替换的 WebClient/provider 边界，尤其用于支持非 HTTP `cse` URI scheme 的运行环境。
- 不增加 Resources、Prompts、运行时目录修改、multipart 上传或固定 Header 配置。
