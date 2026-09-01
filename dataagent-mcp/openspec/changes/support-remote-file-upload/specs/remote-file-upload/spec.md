## Purpose

允许注解 Tool 继续使用字符串文件路径参数，同时将 API Fabric 与 CSE 的远程调用可靠地转换为包含文件和普通表单字段的标准 multipart/form-data 请求。

## ADDED Requirements

### Requirement: 显式声明单个远程文件 part
starter SHALL 允许 API Fabric 与 CSE 的每个端点显式配置一个“下游文件 part 名到 Tool 参数名”的映射。来源 Tool 参数 MUST 为字符串类型并继续以普通字符串字段出现在工具输入 Schema 和调用 arguments 中；配置的文件参数 MUST NOT 再作为普通表单字段或 JSON Body 字段发送。

#### Scenario: 字符串 filePath 映射为文件 part
- **WHEN** 上传端点把下游 part `dsl` 映射到字符串 Tool 参数 `filePath`
- **THEN** 工具 Schema 继续声明 `filePath` 为字符串，远程请求使用 `filePath` 指向的本地文件生成名为 `dsl` 的文件 part

#### Scenario: 非法文件映射阻止目录发布
- **WHEN** 一个端点配置多个文件映射、空白 part 名、未知参数、非字符串参数，或同一来源参数同时用于 Path、Query、业务 Header 与文件 part
- **THEN** starter 在发布任何工具目录前失败，并在英文错误中包含端点类别、引用及问题参数

### Requirement: multipart 普通表单字段
配置文件映射的端点 SHALL 使用 `multipart/form-data`，并在排除 Path、Query、业务 Header 与文件参数后，把其余 Tool 参数按原参数名生成为独立表单字段。标量 MUST 使用应用 Jackson 配置转换为文本；标量集合或数组 MUST 生成同名重复字段；缺失值和 null MUST 省略。multipart 模式 MUST NOT 同时发送 JSON Body，结构化对象或结构化集合 MUST 在目录发布前被拒绝。

#### Scenario: 文件与普通标量参数共同上传
- **WHEN** 调用参数包含 `filePath=/tmp/table.dsl`、`catalog=main` 和 `overwrite=true`，且只有 `filePath` 被配置为文件参数
- **THEN** 请求包含文件 part，以及名为 `catalog` 和 `overwrite` 的文本表单字段，不包含 JSON Body

#### Scenario: 集合生成重复表单字段
- **WHEN** 一个未被其他位置消费的字符串集合参数 `tags` 包含 `one` 和 `two`
- **THEN** multipart 请求按原顺序包含两个名为 `tags` 的文本表单字段

#### Scenario: 不支持的复杂普通参数
- **WHEN** multipart 端点还存在未被其他位置消费的对象参数或对象集合参数
- **THEN** starter 在目录发布前失败，并指出该参数不能作为 multipart 普通表单字段

### Requirement: 文件元数据与内容传输
文件 part SHALL 使用路径末段作为上传文件名，使用可探测的文件媒体类型；无法探测时 MUST 使用 `application/octet-stream`。API Fabric 与 CSE 请求 MUST 以资源方式传输文件内容，不得先把完整文件读入 byte 数组，并 MUST 在请求成功或失败后释放底层资源。

#### Scenario: 发送 DSL 文件
- **WHEN** `filePath` 指向可读的 `/tmp/schema.dsl`
- **THEN** 下游收到文件名为 `schema.dsl`、内容与本地文件一致且媒体类型已设置的 `dsl` part

#### Scenario: 未知文件类型
- **WHEN** 文件媒体类型无法从路径或文件系统探测
- **THEN** 文件 part 使用 `application/octet-stream`

### Requirement: 本地文件运行时校验
starter SHALL 在每次调用时要求文件路径参数非空、语法有效，并指向存在、可读的普通文件。starter SHALL 提供统一的最大上传文件大小配置，默认值为 `100MB`；超过上限时 MUST 在发起下游请求前失败。starter MUST NOT 限制路径根目录或禁止解析到普通文件的符号链接目标。

#### Scenario: 上传合法文件
- **WHEN** filePath 指向上限以内的可读普通文件
- **THEN** starter 发起远程 multipart 请求

#### Scenario: 拒绝无效文件
- **WHEN** filePath 缺失、为空、语法非法、不存在、指向目录、不可读或超过大小上限
- **THEN** starter 不发起下游请求，并返回包含端点引用和原始路径的英文工具错误

#### Scenario: 不限制文件根目录
- **WHEN** filePath 指向应用进程有权读取且满足其他校验的任意普通文件或符号链接目标
- **THEN** starter 不基于根目录白名单拒绝该文件

### Requirement: 保持远程路由与 Header 行为
multipart 端点 SHALL 继续支持 URI template Path、显式 Query、业务 Header 和允许的入站 Header 透传。文件参数和文件内容 MUST NOT 进入透传 Header；文件内容 MUST NOT 进入工具 Schema、arguments 或审计值。业务 Header 与普通表单字段可以具有相同的下游名称，并在各自 HTTP 位置独立发送。

#### Scenario: multipart 与请求其他位置组合
- **WHEN** 一个上传调用同时提供 Path、Query、业务 Header、透传 Header、文件和普通表单参数
- **THEN** 每个值只进入其绑定位置，文件与表单内容通过 multipart 发送，现有 Header 优先级保持不变

#### Scenario: 审计文件上传
- **WHEN** 上传工具被调用
- **THEN** 审计 arguments 可以记录原始 filePath 字符串，但不读取或记录文件内容

### Requirement: API Fabric 与 CSE 行为一致
API Fabric 的 WebClient 路径与 CSE 的 RestOperations 路径 SHALL 发送语义一致的 multipart 请求，包括文件 part 名、文件名、媒体类型、文件内容、普通表单字段和 Header，并继续按注解方法声明的返回类型处理成功响应和下游错误。

#### Scenario: 两类端点上传相同请求
- **WHEN** 等价的 API Fabric 与 CSE 上传端点收到相同 Tool arguments
- **THEN** 两个下游捕获到语义一致的 multipart 内容，且各自继续使用其独立客户端和 URI 规则

#### Scenario: 现有 JSON 端点保持不变
- **WHEN** API Fabric 或 CSE 端点没有配置文件映射
- **THEN** 该端点继续使用现有 JSON Object Body 规则，不生成 multipart 请求

### Requirement: 自动化测试质量门禁
starter SHALL 在 Maven `verify` 阶段使用 JaCoCo 检查全部生产代码，指令覆盖率和分支覆盖率 MUST 均至少为 91%。主工程与同级消费端的每个 JUnit 测试方法 MUST 使用中文 `@DisplayName` 描述被验证的行为，并由自动化测试检查，避免后续新增匿名测试用例。

#### Scenario: 覆盖率不足时构建失败
- **WHEN** starter 的指令覆盖率或分支覆盖率低于 91%
- **THEN** Maven `verify` 失败并报告未满足的覆盖率指标

#### Scenario: 测试用例缺少显示名称
- **WHEN** 主工程或消费端存在未使用 `@DisplayName` 的 JUnit 测试方法
- **THEN** 测试命名策略检查失败并指出对应源码与方法
