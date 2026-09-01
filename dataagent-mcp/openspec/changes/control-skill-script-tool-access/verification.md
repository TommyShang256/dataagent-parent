# Skill/Script 工具权限隔离调测记录

## 1. 验证结论

本轮已把方案收敛为 DataAgent 服务端双标准 MCP 入口，Opencode 源码保持不变：

- Agent endpoint：`/rest/mcp`，服务端固定绑定 `AGENT`；
- Script endpoint：`/rest/mcp/script`，服务端固定绑定 `SCRIPT`；
- 两个入口分别运行官方 Streamable HTTP MCP transport 和独立 session；
- Tool 扫描和业务 invoker 只有一份，按 `allowedCallers` 发布到对应目录；
- caller 只由 endpoint 决定，客户端元数据不参与 caller 判定；
- Script 不要求 Skill、Script、父调用或 Trace 来源字段，无状态 CLI 可直接发送标准 `tools/call`；
- Runner 已收敛为带 `#!/usr/bin/env python3` shebang 的单文件 Python 客户端，不再使用 Shell 包装或 Maven 分发模块；
- starter 与 BFF 配置统一使用 `dataagent.mcp`，不保留 `opencode.mcp` 兼容绑定；
- `/Users/tommy/projects/opencode` 已恢复到仓库 `HEAD`，工作区完全干净。

## 2. 工具权限与目录矩阵

`dataagent-web` 示例工具的实测结果如下：

| Tool | `allowedCallers` | Agent `tools/list` | Script `tools/list` | 合法调用 |
| --- | --- | --- | --- | --- |
| `create_order` | `AGENT` | 可见 | 不可见 | Agent 成功 |
| `upload_table` | `AGENT, SCRIPT` | 可见 | 可见 | Agent、Script 均成功 |
| `validate_table` | `SCRIPT` | 不可见 | 可见 | Script 成功 |

不允许的 Tool 没有发布到对应 MCP Server。标准 Client 构造错误调用时，Server 返回 JSON-RPC `Unknown tool`，本地方法、API Fabric 与 CSE 均不会执行。注册表单元测试还绕过目录直接构造调用，确认执行期第二道 `allowedCallers` 校验仍会拒绝。

## 3. 标准 MCP 请求链

### 3.1 Agent

`ApiFabricOpenCodeE2eTest` 在 Opencode 仓库无任何修改的状态下，直接使用其现有 `MCPClient`：

1. 连接随机端口的 `/rest/mcp`；
2. 完成标准 initialize 和 `tools/list`；
3. 目录只包含 `create_order` 与 `upload_table`；
4. 调用 JSON `create_order`，验证 Path、Query、业务 Header、JSON Body 与返回对象；
5. 调用共享 multipart `upload_table`，验证文件和普通 part；
6. 服务端审计 caller 固定为 `AGENT`。

旧 Opencode Client 不保留自定义 Tool `_meta`，但这不影响目录和调用，因为调用者隔离完全由服务端入口执行。

### 3.2 Script

`DataAgentMcpIntegrationTest` 使用官方 Java MCP Client 连接 `/rest/mcp/script`：

1. 完成标准 initialize 和 `tools/list`；
2. 目录只包含 `upload_table` 与 `validate_table`；
3. `tools/call` 不携带自定义来源 `_meta`；
4. 成功调用共享 multipart Tool 和 Script-only JSON Tool；
5. 审计仅记录 `caller=SCRIPT`；
6. 文件、普通参数和远程结果均符合预期。

Script 请求没有专用 Token、环境变量、私有 Header 或非标准 HTTP API。

### 3.3 单文件 Script Runner

`dataagent-runner/bin/dataagent-runner` 使用 `#!/usr/bin/env python3` 直接调用 PATH 中的 Python 3，
仅依赖标准库，不经过 Shell、sed 或 `python3 -c`，也不依赖 jq、curl、pip、JAR 或第三方 Python 包。
`dataagent-web` 集成测试验证了以下链路：

1. `--help` 与 `--version` 不依赖服务地址即可执行；
2. 缺少 `POD_IP` 返回退出码 3，非对象 JSON 在连接前返回退出码 2；
3. `--list` 完成 initialize、initialized notification 和 `tools/list`，并返回隔离后的
   `upload_table`、`validate_table`；
4. MCP Session ID 与协商后的协议版本 Header 会在后续请求中自动携带，真实 BFF 日志记录 Runner 使用
   `2025-06-18` 初始化；
5. Python Runner 连接真实启动的 BFF Script endpoint，调用 `validate_table` 后 API Fabric mock 收到一次请求，
   审计为 `caller=SCRIPT`。

## 4. 拒绝路径

自动化用例确认以下拒绝都发生在业务 invoker 和模拟 API Fabric 之前：

| 场景 | 拒绝位置 | 结果 |
| --- | --- | --- |
| Agent 调用 Script-only Tool | Agent MCP 目录 | JSON-RPC `Unknown tool`，下游零调用 |
| Script 调用 Agent-only Tool | Script MCP 目录 | JSON-RPC `Unknown tool`，下游零调用 |
| 绕过目录使用错误 caller | 注册表执行期授权 | `SecurityException`，invoker 不执行 |
| Agent 与 Script endpoint 配置相同 | Spring 启动期 | 应用启动失败并指出冲突路径 |

caller 不进入 Tool input Schema、arguments、普通透传 Header、Path、Query、JSON Body 或 multipart part。MCP Server 不定义或解析 Skill/Script 来源字段。

## 5. 身份认证边界

双入口把服务端授权来源固定为 Agent 或 Script，但路径本身不是身份凭证。生产部署必须在 MCP Servlet 之前使用网关、Spring Security、OAuth scope、mTLS 或网络策略保护 `/rest/mcp/script`，只允许可信 Script workload 访问。

如果匿名暴露 Script endpoint，其他客户端也可以主动访问该路径；此时 Tool 目录和执行期策略仍按 `SCRIPT` 正确生效，但系统无法证明请求来自某个真实 Script。当前认证与授权粒度明确为 Agent/Script，不提供 Skill/Script 级身份。

## 6. 自动化与质量门禁

### 6.1 父工程完整验证

执行：

```bash
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn clean verify
```

结果：`BUILD SUCCESS`。

| 模块 | 测试 | 失败 | 错误 | 跳过 | 指令覆盖率 | 分支覆盖率 | 行覆盖率 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `dataagent-mcp` | 83 | 0 | 0 | 0 | 95.87% | 91.53% | 95.85% |
| `dataagent-web` | 18 | 0 | 0 | 0 | 100.00% | 无生产分支 | 100.00% |

回归覆盖 JSON、同名 Header/Body、普通 Header 透传、API Fabric、CSE、multipart 文件、`@RequestParam`、文本 `@RequestPart`、目录隔离、caller 审计与拒绝下游零调用。所有测试方法继续由策略测试检查 `@DisplayName`。
其中 Web 集成测试包含 3 个 Runner 用例，覆盖基础 CLI 与输入错误、真实 Script 目录查询以及真实
BFF/API Fabric 的 Script-only Tool 调用。

### 6.2 JavaDoc

执行：

```bash
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn javadoc:aggregate
```

结果：`BUILD SUCCESS`。生成过程仅报告项目原有默认构造器注释警告，没有 JavaDoc 错误。

### 6.3 OpenSpec

在 `dataagent-mcp` 模块执行：

```bash
openspec validate control-skill-script-tool-access --strict
```

结果：`Change 'control-skill-script-tool-access' is valid`。

### 6.4 构件检查

starter JAR 包含 `McpToolRegistry`、双 Server 自动配置和 `spring-configuration-metadata.json`，不再包含 `ToolCallSource`；BFF 可执行 JAR 包含 `mcp-config.yml` 与最新 `dataagent-mcp` 依赖。配置元数据和示例配置均使用 `dataagent.mcp`。

Runner 不进入 Maven 构建产物，也不生成 ZIP/TAR。`dataagent-runner` 目录只有权限为 `0755` 的
`bin/dataagent-runner`；首行为 `#!/usr/bin/env python3`，直接执行 `--version` 与 `--help` 成功。

### 6.5 运行实例验证

使用最新构建的 Web JAR 启动：

```bash
java -jar dataagent-web/target/dataagent-web-0.1.0-SNAPSHOT.jar \
  --server.address=127.0.0.1 --server.port=8080
```

应用成功注册 `create_order`、`upload_table`、`validate_table` 并监听 `127.0.0.1:8080`。随后执行：

```bash
POD_IP=127.0.0.1 POD_PORT=8080 dataagent-runner/bin/dataagent-runner --list
```

Runner 成功完成初始化并只返回 Script 目录中的 `upload_table` 与 `validate_table`；Agent-only 的
`create_order` 未暴露。服务端日志确认客户端名称为 `dataagent-runner`、版本为 `0.1.0-SNAPSHOT`、
协议为 `2025-06-18`。

## 7. Opencode 回退证明

在 `/Users/tommy/projects/opencode` 执行：

```bash
git status --short
git diff --check
```

两条命令均无输出。此前在 Opencode 内新增的 Skill Script Runner、Schema、Tool Registry、MCP `_meta`、测试、依赖和文档修改已全部撤销；独立 Runner 位于 DataAgentSelf 工程，Agent 端到端测试使用的仍是未修改 Opencode Client。

## 8. 最终结论

当前实现满足“Opencode 零改造、Agent 与 Script 复用 DataAgent 统一 Tool 管理、每个 Tool 支持 Agent-only、Script-only 或共享、使用标准 MCP 请求”的目标。Script 可通过单文件 Python Runner 查询和调用工具，运行环境只需 Python 3 标准库。服务端目录隔离负责减少暴露面，执行期授权负责最终防御，部署层负责确认谁有权进入 Script endpoint，三者职责没有混用。
