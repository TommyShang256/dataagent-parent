## Purpose

提供开箱即用、无状态且只面向 Script MCP 入口的一次性工具调用 CLI。

## ADDED Requirements

### Requirement: Runner 必须使用部署注入的 Pod 地址

Runner SHALL 从 `POD_IP` 与 `POD_PORT` 构造 `http://${POD_IP}:${POD_PORT}/rest/mcp/script`，MUST NOT 回退到 localhost，也 MUST NOT 允许 CLI 覆盖服务地址。

#### Scenario: Pod 地址有效
- **WHEN** `POD_IP` 与 `POD_PORT` 可以组成合法 HTTP 地址
- **THEN** Runner 使用该地址初始化标准 Streamable HTTP MCP Client

#### Scenario: Pod 地址无效
- **WHEN** 任一变量缺失、端口越界或地址语法非法
- **THEN** Runner 在建立网络连接前失败
- **AND** 标准错误输出准确的英文配置错误

### Requirement: Runner 必须查询完整 Script 工具目录

Runner SHALL 通过 `--list` 输出 Script MCP Server 的完整 Tool 定义，并 MUST 遍历 `tools/list` 的全部分页。

#### Scenario: 用户查询可用工具
- **WHEN** 用户执行 `dataagent-runner --list`
- **THEN** Runner 完成 initialize 和全部 `tools/list` 分页
- **AND** 标准输出包含按名称排序的 JSON Tool 定义

### Requirement: Runner 必须在调用前预检工具

Runner SHALL 在每次 `tools/call` 前重新获取完整目录，并按区分大小写的精确名称确认 Tool 存在。

#### Scenario: 工具存在
- **WHEN** 指定名称出现在 Script 工具目录
- **THEN** Runner 使用原始 JSON 对象发送一次标准 `tools/call`

#### Scenario: 工具不存在
- **WHEN** 指定名称不在 Script 工具目录
- **THEN** Runner 返回工具不存在退出码
- **AND** 不发送 `tools/call` 或任何下游业务请求

### Requirement: Runner CLI 必须保持最小参数面

Runner SHALL 支持 `dataagent-runner <tool-name> [arguments-json]`，未提供参数时使用空对象，参数为 `-` 时从 stdin 读取。Runner MUST NOT 提供 caller、Skill ID、Script ID、MCP URL 或通用 HTTP Header 参数。

#### Scenario: 传递 JSON 参数
- **WHEN** 第二个位置参数或 stdin 是 JSON 对象
- **THEN** Runner 将其作为未经位置改写的 Tool arguments

#### Scenario: 参数不是 JSON 对象
- **WHEN** 参数无效或是数组、标量
- **THEN** Runner 在连接 BFF 前返回 CLI 输入错误

### Requirement: HTTP 业务 Header 必须通过 Tool 参数表达

Runner SHALL 把原始 HTTP 接口的业务 Header 当作普通 Tool arguments，不得通过额外 CLI Header 通道绕过 Tool Schema。BFF SHALL 继续根据 `headers.business` 把对应参数映射为下游 Header。

#### Scenario: Header 与 Body 同名
- **WHEN** Tool Schema 使用 `headerA` 表示 Header `A`，并使用 `A` 表示同名 Body 字段
- **THEN** Runner 在同一个 arguments 对象中发送两个不同键
- **AND** BFF 分别生成 Header 值和 Body 值

### Requirement: Runner 必须保持一次性 MCP 生命周期

Runner SHALL 在单次进程中 initialize、查询目录、可选调用、关闭客户端并退出，不得持久化 MCP Session 或工具目录。`tools/call` MUST NOT 自动重试。

#### Scenario: 单次调用完成
- **WHEN** Tool 返回成功、业务错误或传输错误
- **THEN** Runner 关闭临时 MCP Client
- **AND** 标准输出只包含成功获得的 MCP JSON 结果，诊断只写入标准错误

### Requirement: Runner 必须提供可归档分发包和中文手册

构建 SHALL 生成只包含单文件 `bin/dataagent-runner` 与中文 README 的 ZIP 和 TAR.GZ 分发包。Runner SHALL 仅依赖 Linux Shell、sed 和 Python 3 标准库，MUST NOT 依赖 jq、curl、pip、JAR 或第三方 Python 包。

#### Scenario: 服务器安装
- **WHEN** 运维解压分发包并把 `bin` 加入 PATH
- **THEN** Script 可以从任意工作目录直接执行 `dataagent-runner`
