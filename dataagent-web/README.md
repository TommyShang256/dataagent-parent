# dataagent-web BFF

`dataagent-web` 是 DataAgentSelf 的可执行 Spring Boot BFF。它依赖 `dataagent-mcp`，通过 `/rest/mcp` 发布标准 MCP Tools，并把工具调用映射到 API Fabric HTTP 接口。

## 启动

先在父工程构建：

```bash
cd /Users/tommy/projects/DataAgentSelf
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn clean verify
```

使用本地默认 API Fabric 地址 `http://127.0.0.1:18080/api` 启动：

```bash
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn -pl dataagent-web spring-boot:run
```

指向其他 API Fabric 环境时只需覆盖环境变量，无需重新编译：

```bash
DATAAGENT_API_FABRIC_BASE_URL=http://127.0.0.1:19090/api \
DATAAGENT_CREATE_ORDER_PATH='/orders/{orderId}' \
DATAAGENT_UPLOAD_TABLE_PATH=/tables \
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn -pl dataagent-web spring-boot:run
```

仓库不保存真实环境地址、令牌或凭据。认证信息应由部署环境提供，并通过 MCP 入站 Header 透传机制传递允许的 Header。

## 配置文件边界

`application.yml` 只保存 BFF 应用名称并通过 `spring.config.import` 导入 MCP 配置。MCP 服务元数据、API Fabric 基础地址和工具端点映射统一保存在 `mcp-config.yml`，不直接放入 `application.yml`。

两个文件都进入同一个 Spring Environment，因此环境变量、命令行参数和部署平台配置仍可覆盖 `mcp-config.yml` 中的本地默认值。

## MCP Client 配置

OpenCode 等支持 streamable HTTP 的 MCP Client 可配置为：

```json
{
  "mcp": {
    "dataagent-web": {
      "type": "remote",
      "url": "http://127.0.0.1:8080/rest/mcp",
      "oauth": false
    }
  }
}
```

标准 MCP 初始化完成并获得 `Mcp-Session-Id` 后，可以发送 `tools/list`：

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/list",
  "params": {}
}
```

## JSON 工具

`create_order` 的 MCP 调用参数示例：

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "create_order",
    "arguments": {
      "orderId": "O-1",
      "verbose": true,
      "headerA": "header-value",
      "A": "body-value",
      "customerId": "C-1"
    }
  }
}
```

远程请求映射如下：

| MCP 输入 | API Fabric 位置 |
| --- | --- |
| `orderId` | Path `/orders/{orderId}` |
| `verbose` | Query `verbose` |
| `headerA` | 业务 Header `A` |
| `A` | JSON Body 字段 `A` |
| `customerId` | JSON Body 字段 `customerId` |

下游 Header 与 Body 均可使用名称 `A`，但 MCP Schema 使用 `headerA` 和 `A` 两个独立输入键，因此值不会互相覆盖。

## 文件上传工具

`upload_table` 的 MCP 调用参数示例：

```json
{
  "jsonrpc": "2.0",
  "id": 4,
  "method": "tools/call",
  "params": {
    "name": "upload_table",
    "arguments": {
      "filePath": "/absolute/path/table.dsl",
      "catalog": "analytics",
      "description": "create analytics table"
    }
  }
}
```

远程请求使用 `multipart/form-data`：

| MCP 输入 | multipart 位置 |
| --- | --- |
| `filePath` | 文件 part `dsl`，文件名和内容从本地文件读取 |
| `catalog` | 普通表单字段，对应目标接口的 `RequestParam` |
| `description` | 文本表单 part，对应目标接口的非文件 `RequestPart` |

`filePath` 必须是运行 BFF 进程所在机器上的绝对或可解析路径。MCP 模块只校验文件存在、为普通文件、可读且不超过上传大小限制；本模块不提供文件沙箱。文件不存在或不可读取时，调用在发起远程请求前失败。

## 本地验证

`DataAgentMcpIntegrationTest` 使用随机端口启动真实 BFF 和本地模拟 API Fabric，并通过官方 MCP Java Client 完成初始化、`tools/list` 与 `tools/call`，断言 JSON 和 multipart 的完整 HTTP 请求。运行：

```bash
cd /Users/tommy/projects/DataAgentSelf
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn -pl dataagent-web -am clean verify
```
