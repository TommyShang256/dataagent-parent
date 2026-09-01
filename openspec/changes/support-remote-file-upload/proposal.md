## Why

当前远程工具只能把剩余参数发送为 JSON Object Body，无法代理 API Fabric 或 CSE 中以
`multipart/form-data` 接收 `MultipartFile` 的上传接口。MCP Tool 应继续只暴露可理解、可审计的
字符串 `filePath`，由 starter 在远程调用时读取本地文件并转换为标准 multipart 请求。

## What Changes

- 为 API Fabric 与 CSE 端点增加显式的文件 part 映射，将下游 part 名关联到字符串 Tool 参数；首期每个端点支持一个文件 part。
- 配置文件映射的端点使用 `multipart/form-data`：文件参数生成带文件名和媒体类型的文件 part，排除其他位置参数后的普通标量或标量集合参数按原名生成独立表单字段。
- 保持 URI template Path、Query、业务 Header、入站 Header 透传、返回类型转换和审计行为；文件参数仍以字符串出现在工具 Schema 与 arguments 中，文件内容不进入 Schema、arguments 或审计。
- 在发布工具目录前校验文件映射、参数位置冲突及 multipart 普通参数类型；在调用时校验路径非空、文件存在、为普通文件、可读且不超过配置上限，并返回包含端点引用和路径的准确错误。
- API Fabric 使用其独立 WebClient、CSE 使用应用提供的 `cseRestOperations` 发送 multipart 请求；两条路径都以文件资源方式传输并确保请求完成后不遗留打开的文件句柄。
- 不限制 `filePath` 的根目录或符号链接目标；文件系统沙箱由后续 BFF 能力负责。
- 更新配置元数据、README、消费端示例和完整调测记录，并覆盖文件、普通表单参数、Header、错误与资源边界的自动化测试。

## Capabilities

### New Capabilities

- `remote-file-upload`: 定义 Tool 字符串文件路径到 API Fabric/CSE multipart 文件 part 的绑定、普通表单字段组装、文件校验和双客户端传输契约。

### Modified Capabilities

无。

## Impact

- 影响端点配置属性、共享远程参数绑定与请求模型、`ApiFabricToolEndpointHandler`、`CseToolEndpointHandler`、配置元数据和 README。
- API Fabric/CSE 现有 JSON 端点配置与行为保持不变；只有显式声明文件 part 的端点切换为 multipart 请求。
- Tool 声明仍只使用普通 Java `String filePath` 参数，不引入 MCP Resources、二进制 Tool 参数或运行时目录能力。
- 需要在 starter 与同级消费端覆盖 WebClient 和 RestOperations 的真实 multipart 请求捕获，并复跑 OpenCode 标准 MCP client 调用链。
