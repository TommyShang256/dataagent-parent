## 1. 客户端依赖与端点配置

- [x] 1.1 增加 Spring WebFlux 客户端依赖且不改变 Servlet Server 技术栈；通过依赖检查确认可以使用 `WebClient`，并通过现有 Servlet 自动配置测试确认 MCP 基础设施仍基于 Tomcat 启动
- [x] 1.2 扩展 MCP 配置属性，支持 API Fabric `base-url`、按引用配置的 path 端点和按引用配置的完整 CSE URI 端点；通过属性绑定测试覆盖空配置、有效配置和非法配置
- [x] 1.3 使用确定性集合建模 Query 和业务 Header 配置，并更新配置元数据；通过元数据和绑定测试确认其符合文档 YAML，且不存在 Path、Body、透传列表或固定 Header 配置属性
- [x] 1.4 增加可替换的远程客户端边界，API Fabric 使用独立 WebClient，CSE 使用应用提供的 RestOperations

## 2. 请求 Context 与 Header 边界

- [x] 2.1 增加不可变调用 context 和向后兼容的 context-aware `ToolInvoker` overload；通过测试确认现有 lambda invoker 仍可编译运行，且 context-aware invoker 能收到请求元数据
- [x] 2.2 安装 Servlet transport context extractor，捕获当前请求中除系统排除项外的全部 Header 及其所有值；通过测试确认普通 Header 默认进入 context，连接控制、内容协商和 MCP 协议 Header 被排除
- [x] 2.3 将 transport context 通过实际 MCP call handler 传递，并确保审计包装不丢失 context；通过测试确认两次调用的不同 Header 值相互隔离，且本地工具继续忽略 context
- [x] 2.4 实现不区分大小写的 Header 处理和运行时 CR/LF 防护，拒绝业务 Header 使用系统排除名称，并在透传时过滤系统 Header；通过聚焦测试覆盖业务同名覆盖、受限名称、多值和恶意值

## 3. 启动绑定与远程调用

- [x] 3.1 将注解工具名称和端点属性编译为不可变的 API Fabric/CSE 调用计划，同时保持未匹配工具为本地调用；通过 scanner 测试确认远程方法体不会执行，且最终类型元数据正确
- [x] 3.2 在 MCP Server 注册前验证完整绑定集合，包括跨类别重复 ref、未知 ref、URI 占位符没有同名工具参数、Query/业务 Header 引用未知参数或发生位置冲突、重复下游名称及非法 method/URI；通过测试确认每个错误都包含类别、ref 和问题参数，且不会发布部分目录
- [x] 3.3 从 `path-template` 或 CSE `uri-template` 占位符自动识别同名 Path 参数，使用 `base-url` 和模板构建 API Fabric URI，并保留展开后的完整 CSE URI；通过测试验证无需 Path 配置、path 编码、method 选择、基础路径处理、占位参数缺失，以及 `cse://service-name/...` 原样传给 CSE client
- [x] 3.4 从工具参数映射 Query 参数和 Agent 提供的业务 Header，包括目标重命名、可选值省略、Jackson 标量转换和集合重复值；通过捕获的下游请求确认每个参数只出现在算法确定的位置
- [x] 3.5 将调用 context 中除业务 Header 同名项和系统排除项外的其他 Header 默认原样复制到下游请求，且不审计其值；通过测试验证无需透传配置、业务参数值优先、多值保留、系统项过滤，以及透传值不会进入 Schema、arguments 和审计记录
- [x] 3.6 排除自动识别的 Path 参数及显式配置的 Query、业务 Header 参数后，将剩余工具参数按原名自动组装为 JSON Object Body；通过测试确认无需 Body 配置、缺失可选字段被省略、显式 null 和结构化值被保留、根 Schema 保持展开，且没有剩余参数时不发送请求体
- [x] 3.7 通过 WebClient 执行远程调用，并使用注解返回类型转换成功的 JSON、String、泛型、null 和 void-like 响应；通过测试确认非成功状态、connector 错误、超时、URI 错误和转换失败均成为 MCP 工具错误且不会使应用失效

## 4. 自动配置、消费端模块与文档

- [x] 4.1 使用条件自动配置连接 endpoint catalog、binder、context extractor、WebClient provider、scanner 和 registry；通过测试确认未配置端点的应用保持现有本地行为，禁用 MCP 时也不会创建任何相关基础设施
- [x] 4.2 在同级 `dataagent-mcp-test` 应用中配置 API Fabric 和 CSE 端点，并将代表性的本地实现改为使用模板自动 Path、显式 Query、业务 Header、默认 Header 透传和自动剩余 Body 参数的注解代理工具；通过测试确认应用以预期的固定工具目录启动
- [x] 4.3 使用分别捕获 WebClient 和 RestOperations 请求的实现增加消费端集成测试；端到端验证 API Fabric/CSE method 与 URI 构造、请求参数位置、Header 分离、Body 展开、响应转换、本地 fallback 和下游错误映射
- [x] 4.4 更新 README 和配置示例，说明 endpoint ref、模板自动 Path、显式 Query/业务 Header、剩余参数自动 Body、普通入站 Header 默认透传及系统排除项、CSE RestTemplate 责任、同步执行和敏感业务 Header 审计建议；通过检查确认文档中的每个属性和公开扩展类型都真实存在
- [x] 4.5 将 `multipart/form-data` 文件上传记录为后续待办，覆盖文件及表单字段、Content-Type、大小限制、流式传输和资源清理；通过文档检查确认当前版本明确只支持 JSON Body

## 5. 最终验证

- [x] 5.1 安装更新后的 starter，并使用 Java 21 release 编译分别对 `dataagent-mcp` 和 `dataagent-mcp-test` 执行 clean verification；确认全部单元、context、请求捕获、Schema 和 MCP 侧测试通过，且不存在陈旧 target class
- [x] 5.2 检查最终 diff、公开 API、配置元数据、Server capabilities 和构建 JAR；确认不存在固定 Header 功能、multipart 实现、Resource/Prompt capability、运行时修改 API、CSE URI 改写、无关变更或陈旧包引用
- [x] 5.3 执行严格 OpenSpec 校验，并将每个场景对应到通过的自动化测试或明确交付的文档检查

## 6. 端点处理 SPI 重构

- [x] 6.1 增加公共远程端点处理接口，由 scanner 集中执行目录级匹配、未知引用及跨实现重复引用校验；通过测试确认未匹配工具仍执行本地方法
- [x] 6.2 将 API Fabric 和 CSE 提升为两个独立默认实现，并抽取共享远程调用计划组件；通过既有请求映射测试确认 URI、参数、Header、Body、响应和错误行为不变
- [x] 6.3 调整自动配置，使 API Fabric 与 CSE 默认实现可以分别替换且互不影响；通过上下文测试覆盖只替换任一实现及增加扩展实现
- [x] 6.4 更新 README 和公开 API 文档，执行 starter、消费端及严格 OpenSpec 验证，并将最新代码加入 Git 暂存区
- [x] 6.5 删除未上线的旧 `RemoteToolEndpointBinder` 兼容适配器，将测试直接切换到公共 handler SPI 和 scanner，并重新完成全量验证

## 7. 结构精简与源码文档

- [x] 7.1 将端点目录汇总和绑定直接收敛到 scanner，删除仅转发的 `ToolEndpointBinder`、`CompositeToolEndpointBinder` 与 `ToolMethodRegistration`；通过目录校验、扩展实现和远程请求测试确认行为不变
- [x] 7.2 删除单字段响应包装类型及无逻辑显式构造器，检查生产源码类型和方法数量，确保不通过混合职责换取表面精简
- [x] 7.3 为 starter 与消费端全部 Java 源文件的顶层类、接口、record、enum 或注解声明补充职责说明、`@author beining.shang` 和 `@since 2026-08-31`，并执行 JavaDoc、全量测试、严格 OpenSpec 和 Git 暂存验证

## 8. 统一工具声明路径

- [x] 8.1 删除未被消费端使用的 `RemoteToolClient` 公开 API、scanner 分支及专用测试，将自动配置测试改为仅使用 `@Tool` 工具并确认固定目录、审计和调用行为不变
- [x] 8.2 更新 README、设计和验证材料，执行 starter 与消费端全量测试、JavaDoc、严格 OpenSpec 校验和 Git 暂存检查

## 9. 来源与调用上下文内联

- [x] 9.1 在 `Tool` 内声明最终工具类型枚举，以类型字段替代 `ToolOrigin`，同步 scanner、端点处理器、WebClient provider、审计事件和测试，并删除重复的来源标识
- [x] 9.2 将只承载请求 Header 的 `ToolInvocationContext` 内联为不可变多值 Header 映射，保持函数式 `ToolInvoker`、审计包装、Header 透传和请求隔离行为，并删除独立上下文类型
- [x] 9.3 更新 README、设计和验证材料，执行 starter 与消费端全量测试、JavaDoc、严格 OpenSpec 校验、结构计数和 Git 暂存检查

## 10. 工具行为属性内联

- [x] 10.1 删除 `ToolHints`，将四个 `@Tool` 行为属性直接保存到 `ToolRegistration`，同步 scanner、registry 和测试，并确认本地与远程绑定完整保留 SDK ToolAnnotations
- [x] 10.2 更新设计与验证材料，执行 starter、消费端、JavaDoc、严格 OpenSpec、结构计数和 Git 暂存检查

## 11. Remote 包结构收敛

- [x] 11.1 将 `RemoteHeaderPolicy` 与 `ServletToolContextExtractor` 合并为单一请求 Header 边界，收窄策略方法可见性，并通过提取、系统排除、CR/LF 和远程透传测试确认行为不变
- [x] 11.2 将共享调用组件重命名为 `RemoteToolInvokerBinder`，使用 Lombok 消除内部构造样板、绑定期预计算业务 Header 名称、删除不可达响应分支，并增加 API Fabric `path-template` 路径校验测试
- [x] 11.3 更新验证材料，执行 starter、消费端、JavaDoc、严格 OpenSpec、结构计数、JAR 与 Git 暂存检查

## 12. 英文日志约束

- [x] 12.1 检查 starter 与消费端生产代码的全部日志调用，确保固定文本和字段名使用英文，并增加自动化源码约束防止中文日志模板回归
- [x] 12.2 更新验证材料，执行 starter、消费端、JavaDoc、严格 OpenSpec、日志扫描和 Git 暂存检查

## 13. 生产运行时字符串英文化

- [x] 13.1 将 starter 与消费端生产代码中的中文异常、断言及位置名称改为英文，同步测试诊断断言，并将自动化约束升级为扫描全部生产字符串字面量
- [x] 13.2 更新验证材料，执行 starter、消费端、JavaDoc、严格 OpenSpec、生产字符串扫描和 Git 暂存检查

## 14. CSE RestTemplate 扩展

- [x] 14.1 删除 CSE 对 WebClient 和 `RemoteToolWebClientProvider` 的依赖，为 API Fabric 提供独立 WebClient，并允许应用提供 CSE RestOperations
- [x] 14.2 使用 `RestOperations.exchange(url, method, requestEntity, responseType)` 执行 CSE URI、method、Header、Body 和泛型响应映射，并覆盖 CSE 客户端缺失、请求捕获和错误映射测试
- [x] 14.3 更新 README 与验证材料，执行 starter、消费端、JavaDoc、严格 OpenSpec、生产字符串扫描、JAR 和 Git 暂存检查

## 15. 远程调用实现按工具类型隔离

- [x] 15.1 将 API Fabric WebClient 调用与响应转换显式移入 `ApiFabricToolEndpointHandler`，使其不再依赖 CSE provider
- [x] 15.2 将 CSE RestOperations 取得、`HttpEntity<Object>` 构造和 `exchange` 调用显式移入 `CseToolEndpointHandler`，使其不再依赖 API Fabric WebClient；共享绑定工厂只保留参数映射
- [x] 15.3 增加依赖隔离与两类调用路径测试，同步 README 和验证材料，执行 starter、消费端、JavaDoc、严格 OpenSpec、JAR 和 Git 暂存检查

## 16. CSE 客户端直接注入

- [x] 16.1 删除只包装客户端的 `CseRestTemplateProvider`，使 `CseToolEndpointHandler` 与 API Fabric handler 一样在构造时直接接收并持有自己的客户端
- [x] 16.2 自动配置按名称解析可选的 `cseRestOperations` Bean；配置 CSE ref 但客户端缺失时继续在发布目录前失败，未配置 CSE ref 时不影响启动
- [x] 16.3 同步 starter、消费端、README、协作记忆和验证材料，执行双模块测试、JavaDoc、严格 OpenSpec、结构及 JAR 检查并暂存最新改动

## 17. 函数参数数量收敛

- [x] 17.1 增加编译后签名约束测试，覆盖 starter 与消费端生产、测试类的非 synthetic 方法和构造器，确保参数数量最多为 5
- [x] 17.2 使用有明确职责的嵌套不可变值重构注册、审计和远程绑定签名，拆分测试高参数方法并收敛消费端代理工具参数，同时保持远程请求、响应、Schema 和审计行为
- [x] 17.3 更新验证材料，执行 starter 与消费端 clean verify、JavaDoc、严格 OpenSpec、生产字符串、结构、JAR、diff 和 Git 暂存检查
