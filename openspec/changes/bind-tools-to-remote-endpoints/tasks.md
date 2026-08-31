## 1. 客户端依赖与端点配置

- [x] 1.1 增加 Spring WebFlux 客户端依赖且不改变 Servlet Server 技术栈；通过依赖检查确认可以使用 `WebClient`，并通过现有 Servlet 自动配置测试确认 MCP 基础设施仍基于 Tomcat 启动
- [x] 1.2 扩展 MCP 配置属性，支持 API Fabric `base-url`、按引用配置的 path 端点和按引用配置的完整 CSE URI 端点；通过属性绑定测试覆盖空配置、有效配置和非法配置
- [x] 1.3 使用确定性集合建模 Query 和业务 Header 配置，并更新配置元数据；通过元数据和绑定测试确认其符合文档 YAML，且不存在 Path、Body、透传列表或固定 Header 配置属性
- [x] 1.4 增加可替换的远程 WebClient provider，并提供默认 API Fabric/CSE client；通过测试确认应用自定义 provider 能替换默认实现并接收自定义 `cse` connector

## 2. 请求 Context 与 Header 边界

- [x] 2.1 增加不可变调用 context 和向后兼容的 context-aware `ToolInvoker` overload；通过测试确认现有 lambda invoker 仍可编译运行，且 context-aware invoker 能收到请求元数据
- [x] 2.2 安装 Servlet transport context extractor，捕获当前请求中除系统排除项外的全部 Header 及其所有值；通过测试确认普通 Header 默认进入 context，连接控制、内容协商和 MCP 协议 Header 被排除
- [x] 2.3 将 transport context 通过实际 MCP call handler 传递，并确保审计包装不丢失 context；通过测试确认两次调用的不同 Header 值相互隔离，且本地工具继续忽略 context
- [x] 2.4 实现不区分大小写的 Header 处理和运行时 CR/LF 防护，拒绝业务 Header 使用系统排除名称，并在透传时过滤系统 Header；通过聚焦测试覆盖业务同名覆盖、受限名称、多值和恶意值

## 3. 启动绑定与远程调用

- [x] 3.1 将注解工具名称和端点属性编译为不可变的 API Fabric/CSE 调用计划，同时保持未匹配工具为本地调用且通用 `RemoteToolClient` 注册不变；通过 scanner 测试确认远程方法体不会执行，且 origin 元数据正确
- [x] 3.2 在 MCP Server 注册前验证完整绑定集合，包括跨类别重复 ref、未知 ref、URI 占位符没有同名工具参数、Query/业务 Header 引用未知参数或发生位置冲突、重复下游名称及非法 method/URI；通过测试确认每个错误都包含类别、ref 和问题参数，且不会发布部分目录
- [x] 3.3 从 `path-template` 或 CSE `uri-template` 占位符自动识别同名 Path 参数，使用 `base-url` 和模板构建 API Fabric URI，并保留展开后的完整 CSE URI；通过测试验证无需 Path 配置、path 编码、method 选择、基础路径处理、占位参数缺失，以及 `cse://service-name/...` 原样传给 client provider
- [x] 3.4 从工具参数映射 Query 参数和 Agent 提供的业务 Header，包括目标重命名、可选值省略、Jackson 标量转换和集合重复值；通过捕获的 WebClient 请求确认每个参数只出现在算法确定的位置
- [x] 3.5 将调用 context 中除业务 Header 同名项和系统排除项外的其他 Header 默认原样复制到下游请求，且不审计其值；通过测试验证无需透传配置、业务参数值优先、多值保留、系统项过滤，以及透传值不会进入 Schema、arguments 和审计记录
- [x] 3.6 排除自动识别的 Path 参数及显式配置的 Query、业务 Header 参数后，将剩余工具参数按原名自动组装为 JSON Object Body；通过测试确认无需 Body 配置、缺失可选字段被省略、显式 null 和结构化值被保留、根 Schema 保持展开，且没有剩余参数时不发送请求体
- [x] 3.7 通过 WebClient 执行远程调用，并使用注解返回类型转换成功的 JSON、String、泛型、null 和 void-like 响应；通过测试确认非成功状态、connector 错误、超时、URI 错误和转换失败均成为 MCP 工具错误且不会使应用失效

## 4. 自动配置、消费端模块与文档

- [x] 4.1 使用条件自动配置连接 endpoint catalog、binder、context extractor、WebClient provider、scanner 和 registry；通过测试确认未配置端点的应用保持现有本地行为，禁用 MCP 时也不会创建任何相关基础设施
- [x] 4.2 在同级 `dataagent-mcp-test` 应用中配置 API Fabric 和 CSE 端点，并将代表性的本地实现改为使用模板自动 Path、显式 Query、业务 Header、默认 Header 透传和自动剩余 Body 参数的注解代理工具；通过测试确认应用以预期的固定工具目录启动
- [x] 4.3 使用捕获 WebClient 请求的实现增加消费端集成测试；端到端验证 API Fabric/CSE method 与 URI 构造、请求参数位置、Header 分离、Body 展开、响应转换、本地 fallback 和下游错误映射
- [x] 4.4 更新 README 和配置示例，说明 endpoint ref、模板自动 Path、显式 Query/业务 Header、剩余参数自动 Body、普通入站 Header 默认透传及系统排除项、CSE connector 责任、同步执行和敏感业务 Header 审计建议；通过检查确认文档中的每个属性和公开扩展类型都真实存在
- [x] 4.5 将 `multipart/form-data` 文件上传记录为后续待办，覆盖文件及表单字段、Content-Type、大小限制、流式传输和资源清理；通过文档检查确认当前版本明确只支持 JSON Body

## 5. 最终验证

- [x] 5.1 安装更新后的 starter，并使用 Java 21 release 编译分别对 `dataagent-mcp` 和 `dataagent-mcp-test` 执行 clean verification；确认全部单元、context、请求捕获、Schema 和 MCP 侧测试通过，且不存在陈旧 target class
- [x] 5.2 检查最终 diff、公开 API、配置元数据、Server capabilities 和构建 JAR；确认不存在固定 Header 功能、multipart 实现、Resource/Prompt capability、运行时修改 API、CSE URI 改写、无关变更或陈旧包引用
- [x] 5.3 执行严格 OpenSpec 校验，并将每个场景对应到通过的自动化测试或明确交付的文档检查
