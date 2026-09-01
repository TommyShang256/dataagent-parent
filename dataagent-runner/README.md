# DataAgent Runner 使用手册

DataAgent Runner 是供 Skill/Script 使用的一次性 MCP Client。它是一个可直接放入 `PATH` 的单文件
Linux Shell 程序，内嵌实现仅使用 Python 3 标准库，不依赖 `jq`、`curl`、pip、JAR 或额外 Python 包。

每次命令都会连接 BFF 的 Script MCP 入口，完成 `initialize`、`notifications/initialized` 和
`tools/list`；调用模式会先确认工具存在，再发送一次 `tools/call`，随后进程退出。Runner 不保存
Session、工具目录、Skill ID、Script ID 或调用历史，也不需要修改 Opencode。

## 运行环境

- Linux `/bin/sh`、`sed` 和 `python3`；Python 3 只需标准库。
- Runner 与 BFF 位于同一 Pod 网络，BFF 监听 Pod IP 可达地址。
- 部署环境提供 `POD_IP` 与 `POD_PORT`。

Runner 固定连接：

```text
http://${POD_IP}:${POD_PORT}/rest/mcp/script
```

`POD_PORT` 是 BFF 端口；Opencode 的 `4096` 端口不参与 Runner 到 BFF 的通信。Runner 不回退到
`localhost`，并要求 `POD_IP` 是有效 IPv4/IPv6 地址。

Kubernetes 环境变量示例：

```yaml
env:
  - name: POD_IP
    valueFrom:
      fieldRef:
        fieldPath: status.podIP
  - name: POD_PORT
    value: "8080"
```

## 安装

构建产物位于：

```text
dataagent-runner/target/dataagent-runner-0.1.0-SNAPSHOT-bin.tar.gz
dataagent-runner/target/dataagent-runner-0.1.0-SNAPSHOT-bin.zip
```

解压并加入 `PATH`：

```shell
mkdir -p /opt/dataagent-runner
tar -xzf dataagent-runner-0.1.0-SNAPSHOT-bin.tar.gz -C /opt
export PATH="/opt/dataagent-runner/bin:$PATH"
dataagent-runner --version
```

也可以仅复制单文件：

```shell
install -m 0755 bin/dataagent-runner /usr/local/bin/dataagent-runner
```

## 查看可用工具

```shell
dataagent-runner --list
```

Runner 遍历全部 `tools/list` 分页，按工具名排序后输出完整 JSON Tool 定义。这里只会出现 BFF
授权给 `SCRIPT` 的工具。

## 调用工具

CLI 参数只包含工具名和一个可选 JSON 对象：

```shell
dataagent-runner <tool-name> [arguments-json]
dataagent-runner refresh_metadata
dataagent-runner validate_table '{"catalog":"analytics"}'
```

复杂参数可从标准输入读取：

```shell
dataagent-runner upload_table - <<'JSON'
{
  "filePath": "/data/table.dsl",
  "catalog": "analytics",
  "description": "Daily table"
}
JSON
```

参数必须是 JSON 对象。数组、标量和非法 JSON 会在连接 BFF 前被拒绝。每次调用都会先精确匹配
工具名；工具不存在时不会发送 `tools/call`，也不会触发 API Fabric/CSE 请求。

## 原始 HTTP Header 参数

API Fabric/CSE 接口中的业务 Header 仍是普通 Tool 参数。Runner 原样传递 arguments，由 BFF 的工具
定义决定参数最终进入 Header、Path、Query、Body 或 multipart part；Runner 不提供通用 `--header`。

例如原始接口同时有 Header `A` 和 Body 字段 `A`，工具定义可将 Header 参数映射成 `headerA`：

```yaml
headers:
  business:
    A: headerA
```

调用时同时传递两个 Tool 参数：

```shell
dataagent-runner create_order '{"headerA":"header-value","A":"body-value"}'
```

BFF 会生成 Header `A: header-value` 和 JSON Body `{"A":"body-value"}`。MCP Session 与协议版本 Header
由 Runner 自动管理，不属于 Tool arguments。

## 文件上传

Runner 只传文件路径字符串，不读取或编码文件：

```shell
dataagent-runner upload_table \
  '{"filePath":"/data/table.dsl","catalog":"analytics"}'
```

BFF 根据 `filePath` 读取文件并构造远程 multipart 请求，因此该路径必须对 BFF 进程可见。普通
`RequestParam` 和文本 `RequestPart` 与 `filePath` 一起放在同一个 JSON 参数对象中。

## 输出和退出码

成功结果以 JSON 写入标准输出；诊断写入标准错误，且不会打印完整业务参数。

| 退出码 | 含义 |
| ---: | --- |
| `0` | 工具目录获取或工具调用成功 |
| `2` | CLI 格式或 JSON 参数错误 |
| `3` | `POD_IP`/`POD_PORT` 配置错误 |
| `4` | MCP 连接、协议或远程请求失败 |
| `5` | Script 工具目录中不存在指定工具 |
| `6` | MCP Tool 返回 `isError=true` |
| `7` | MCP 结果无法序列化为 JSON |
| `127` | 系统中没有 `python3` |

## 超时、重试和安全边界

- 初始化和目录请求超时 10 秒；工具调用超时 300 秒。
- `tools/call` 不自动重试，避免非幂等接口重复执行。
- 工具目录不缓存，每个进程读取服务端最新目录。
- Script endpoint 将调用源绑定为 `SCRIPT`，BFF 在工具发现和执行两处实施同一权限策略。
- endpoint 路径本身不是身份认证。生产环境仍应使用沙箱、workload identity、mTLS、网关或网络策略，
  防止不可信进程访问 Script 能力。

## 构建和测试

```shell
mvn -pl dataagent-runner clean verify
mvn clean verify
```

模块测试使用 Python 标准库启动 MCP mock；Web 集成测试还会启动真实 BFF，并由该 Shell Runner 经
`/rest/mcp/script` 调用 API Fabric mock。
