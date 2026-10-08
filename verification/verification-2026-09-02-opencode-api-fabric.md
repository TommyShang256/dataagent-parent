# OpenCode、dataagent-web 与 mock API Fabric 联通验证报告

## 验证结论

验证通过。OpenCode V2 能通过标准 Streamable HTTP MCP 连接 `dataagent-web`，发现并由模型实际调用 Agent 工具；`dataagent-web` 能按配置把调用转换为 API Fabric HTTP 请求；mock API Fabric 能接收并返回结果；结果能沿原链路返回 OpenCode。JSON、multipart 文件上传、Script-only 工具、调用者目录隔离和 OpenCode HTTP Basic Auth 均得到验证。

## 验证时间与版本

- 时间：2026-09-02 09:29—09:34，时区 `Asia/Shanghai`。
- DataAgentSelf：提交 `7410d1a`，验证时包含本报告及测试路径修正。
- OpenCode：目录 `/Users/tommy/projects/opencode`，分支 `v2`，提交 `0fbb83b7a7`。
- Bun：`1.3.14`。
- Java：OpenJDK `26.0.2.1`，生产代码按 Maven `release 21` 编译。

## 运行拓扑

```text
OpenCode V2 :4097
  -> Streamable HTTP MCP /rest/mcp
dataagent-web :8081
  -> API Fabric HTTP /api/*
mock API Fabric :18081
```

本次使用独立端口，未停止或覆盖机器上原有的 `4096/8090/18080` 进程。

| 组件 | 地址 | PID | 状态 |
| --- | --- | ---: | --- |
| OpenCode V2 | `http://127.0.0.1:4097` | 91315 | 监听中 |
| dataagent-web | `http://127.0.0.1:8081` | 91187 | 监听中 |
| mock API Fabric | `http://127.0.0.1:18081/api` | 91146 | 监听中 |

OpenCode 使用临时配置 `/tmp/dataagentself-e2e.QP0O02/opencode.jsonc`，其中 MCP Server `dataagent-web-e2e` 指向 `http://127.0.0.1:8081/rest/mcp`，关闭 OAuth，设置 `codemode=false` 以直接暴露 MCP 工具。dataagent-web 使用环境变量把 API Fabric 基础地址覆盖为 `http://127.0.0.1:18081/api`。本地验证密码未写入仓库或报告。

## 验证结果

### 1. 构建、测试与静态验证

- 首次 `clean verify` 发现集成测试仍引用已经删除的 `dataagent-runner/bin/dataagent-runner`。
- 将测试路径修正为当前单文件 Runner：`dataagent-mcp/src/main/resources/dataagent-runner`；未改变生产代码或运行时行为。
- 修正后执行 `mvn -pl dataagent-web -am clean verify`：成功。
- `dataagent-mcp`：83 个测试，0 失败，0 错误，0 跳过。
- `dataagent-web`：18 个测试，0 失败，0 错误，0 跳过。
- JaCoCo 校验：成功。
- JavaDoc：成功；starter 现有默认构造器产生 10 个警告，不影响生成结果。
- `openspec validate --all --strict`：5 项通过，0 项失败。
- `git diff --check`：通过。
- 可执行 JAR 检查：包含 `ApiFabricTools`、`application.yml` 和 `mcp-config.yml`。

### 2. OpenCode Server 与 MCP 连接

- 携带正确 Basic Auth 调用 `/api/health`：HTTP 200，返回 `healthy=true`。
- 不携带认证调用 `/api/health`：HTTP 401。
- OpenCode MCP 状态从 `pending` 完成连接后变为：

```json
{"name":"dataagent-web-e2e","status":{"status":"connected"}}
```

### 3. 模型驱动 JSON 工具调用

使用 OpenCode `opencode/big-pickle` 模型发起提示，模型实际调用：

```text
dataagent-web-e2e_create_order
```

输入为 `orderId=O-E2E-20260902`、`verbose=true`、`headerA=header-e2e`、JSON 字段 `A=body-e2e`、`customerId=C-E2E`。工具返回：

```json
{"id":"O-E2E-20260902","status":"created"}
```

mock API Fabric 捕获到：

```text
POST /api/orders/O-E2E-20260902?verbose=true
A: header-e2e
Content-Type: application/json
{"A":"body-e2e","customerId":"C-E2E"}
```

这证明 Path、Query、业务 Header 与同名 JSON Body 字段彼此隔离且映射正确。dataagent-web 审计日志记录 `tool=create_order`、`caller=AGENT`、`outcome=SUCCESS`。

### 4. 模型驱动 multipart 文件上传

模型实际调用：

```text
dataagent-web-e2e_upload_table
```

工具输入包含绝对路径 `/tmp/dataagentself-e2e.QP0O02/table.dsl`、`catalog=analytics` 和描述。mock API Fabric 捕获到：

- `POST /api/tables`；
- `Content-Type: multipart/form-data`；
- 文件 part `dsl`，文件名 `table.dsl`，内容为 `create table opencode_e2e(id bigint);`；
- 普通字段 `catalog=analytics`；
- 文本 part `description=opencode end-to-end upload`。

工具与模型最终均返回 `uploaded`。dataagent-web 审计日志记录 `tool=upload_table`、`caller=AGENT`、`outcome=SUCCESS`。

### 5. Script 入口与调用者隔离

单文件 Runner 连接 `http://127.0.0.1:8081/rest/mcp/script`：

- `tools/list` 仅返回 `upload_table` 和 `validate_table`；
- `validate_table` 调用返回 `validated`；
- mock API Fabric 收到 `POST /api/tables/validate` 和 `{"catalog":"runner-e2e"}`；
- dataagent-web 审计日志记录 `caller=SCRIPT`、`outcome=SUCCESS`。

全量集成测试同时断言 Agent 目录不包含 `validate_table`、Script 目录不包含 `create_order`，越权调用在到达 API Fabric 前失败。

## 最终判断

从 OpenCode 模型选取工具、MCP 传输、dataagent-web 工具注册与调用者绑定、API Fabric 参数映射、mock 下游响应到结果回传的完整链路可联通，当前未发现阻断问题。验证环境仍保持运行，可继续手工复测；临时配置和上传样例位于 `/tmp/dataagentself-e2e.QP0O02`。

唯一发现并修正的问题是测试资源路径陈旧。JavaDoc 的 10 个默认构造器警告为既有非阻断项，可单独治理，不影响本次联通结论。
