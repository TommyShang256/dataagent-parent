# dataagent-bff-application Specification

## Purpose
定义 DataAgent 多模块工程及其 BFF 应用的可构建、可启动和可验证行为，使 BFF 能通过 MCP 标准端点发布并调用 API Fabric 远程工具。

## Requirements

### Requirement: DataAgent 统一构建
DataAgent 父工程 SHALL 同时聚合 `dataagent-mcp` 与 `dataagent-web`，并 SHALL 支持从父工程执行一次 Maven 构建完成两个模块的编译、测试、覆盖率校验和打包。

#### Scenario: 从父工程完整构建
- **WHEN** 开发者在 DataAgent 父工程执行 `mvn clean verify`
- **THEN** 系统成功构建 `dataagent-mcp` 和 `dataagent-web`
- **AND** 两个模块的测试与既定覆盖率门禁均通过

#### Scenario: MCP 构件坐标保持稳定
- **WHEN** 父工程完成构建
- **THEN** MCP 模块仍产出 `ai.opencode.mcp:dataagent-mcp:0.1.0-SNAPSHOT`
- **AND** BFF 通过该 Maven 坐标依赖 MCP 模块

### Requirement: BFF 发布 MCP 服务
`dataagent-web` SHALL 是可独立启动的 Spring Boot BFF 应用，并 SHALL 通过 `/rest/mcp` 发布由 `dataagent-mcp` 自动配置的标准 MCP Tools 服务。

#### Scenario: BFF 启动并发现工具
- **WHEN** BFF 使用有效的 API Fabric 基础地址启动
- **THEN** 应用启动成功并在 `/rest/mcp` 接受标准 MCP 请求
- **AND** `tools/list` 返回 BFF 声明的远程工具及其输入 Schema

#### Scenario: 缺少必要远程配置
- **WHEN** BFF 启动时没有获得 API Fabric 基础地址
- **THEN** 应用使用明确的启动期错误拒绝发布不可调用的工具目录
- **AND** 错误信息标识缺失的配置项

### Requirement: 配置 API Fabric JSON 工具
BFF SHALL 声明至少一个 API Fabric JSON 远程工具示例，并 SHALL 展示 Path、Query、业务 Header 与展开 JSON Body 参数的配置方式。

#### Scenario: 调用 JSON 远程工具
- **WHEN** MCP 客户端使用合法参数调用 JSON 示例工具
- **THEN** BFF 向配置的 API Fabric 路径发起对应 HTTP 请求
- **AND** Path、Query、业务 Header 与 JSON Body 参数分别出现在约定位置
- **AND** BFF 将远程响应转换为标准 MCP 工具结果

#### Scenario: Header 与 Body 使用同名参数
- **WHEN** JSON 工具同时声明同名的业务 Header 参数与 Body 参数
- **THEN** 工具输入 Schema 为两者提供互不冲突的输入键
- **AND** 远程请求分别使用对应值填充 Header 与 Body

### Requirement: 配置 API Fabric 文件上传工具
BFF SHALL 声明至少一个 multipart API Fabric 远程工具示例，工具对文件仅暴露本地 `filePath` 字符串输入，同时 SHALL 支持文件之外的普通 `RequestParam` 与文本或 JSON `RequestPart`。

#### Scenario: 文件与 RequestParam 混合上传
- **WHEN** MCP 客户端传入有效文件路径及普通表单参数
- **THEN** BFF 以 `multipart/form-data` 调用远程端点
- **AND** 文件 part 使用目标接口约定的名称、文件名与内容
- **AND** 普通 `RequestParam` 作为独立 multipart 字段发送

#### Scenario: 文件与非文件 RequestPart 混合上传
- **WHEN** MCP 客户端传入有效文件路径及文本或结构化 part 参数
- **THEN** BFF 将文件和非文件参数作为各自独立的 multipart part 发送
- **AND** 每个 part 使用工具配置声明的名称与内容类型

#### Scenario: 文件不可读取
- **WHEN** MCP 客户端传入不存在或不可读取的文件路径
- **THEN** 工具调用失败且不会向远程 API Fabric 发送不完整请求
- **AND** 错误信息包含可定位的工具和文件参数信息

### Requirement: BFF 配置可外部化
BFF SHALL 将 MCP 服务、API Fabric 基础地址和示例远程端点路径集中保存在独立的 `mcp-config.yml` 中，`application.yml` SHALL NOT 直接声明 `opencode.mcp` 配置，仓库 SHALL NOT 包含真实环境凭据、令牌或公司环境专用地址。

#### Scenario: 应用导入独立 MCP 配置
- **WHEN** BFF 使用默认 classpath 配置启动
- **THEN** `application.yml` 显式导入 `mcp-config.yml`
- **AND** MCP 服务与远程工具配置由独立文件完成绑定
- **AND** `application.yml` 不包含 `opencode.mcp` 配置树

#### Scenario: 本地覆盖远程地址
- **WHEN** 开发者通过环境变量或 Spring 配置覆盖 API Fabric 基础地址
- **THEN** BFF 对工具调用使用覆盖后的地址
- **AND** 无需重新编译应用

### Requirement: BFF 集成验证
BFF SHALL 提供不依赖真实公司环境的自动化测试，覆盖应用启动、工具发现、JSON 参数映射、同名 Header/Body 参数以及 multipart 混合参数请求。

#### Scenario: 本地模拟远程端点
- **WHEN** 执行 BFF 测试
- **THEN** 测试使用进程内或本地模拟 HTTP 服务接收远程工具请求
- **AND** 测试断言 HTTP 方法、路径、Header、查询参数、JSON Body、multipart part 和 MCP 响应

#### Scenario: 测试命名与覆盖率门禁
- **WHEN** 执行父工程 `verify`
- **THEN** 每个测试方法均具有中文 `@DisplayName`
- **AND** 生产代码指令覆盖率与分支覆盖率均不低于 90%
