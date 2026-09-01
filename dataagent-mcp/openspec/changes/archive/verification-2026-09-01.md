# 已有提案统一回归与归档记录

## 回归范围

本次于 2026-09-01 对下列全部已完成提案执行统一回归，并在验证通过后归档：

- `trim-mcp-starter`
- `bind-tools-to-remote-endpoints`
- `support-remote-file-upload`
- `create-dataagent-parent-web`

回归覆盖 MCP starter、父工程聚合构建、BFF、API Fabric、CSE、JSON 参数映射、同名 Header/Body
参数隔离、multipart 文件与普通参数混合上传，以及标准 MCP 客户端端到端调用。

## 主工程验证

本机 `PATH` 不包含独立 Maven，验证使用 IntelliJ IDEA 内置 Maven 3.9.16：

```text
/Applications/IntelliJ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn clean verify
```

结果：

```text
DataAgent Parent  SUCCESS
DataAgent MCP     SUCCESS
DataAgent Web     SUCCESS
BUILD SUCCESS

MCP：Tests run: 78, Failures: 0, Errors: 0, Skipped: 1
Web：Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
```

MCP 的 1 个跳过用例是需要外部 opencode 进程的条件测试；本轮标准 MCP 链路由 Web 模块中的官方
MCP Java Client 集成测试完整执行，不依赖该条件用例。

JaCoCo 最终报告：

| 模块 | 指令覆盖率 | 分支覆盖率 |
| --- | ---: | ---: |
| `dataagent-mcp` | 95.70%（4698/4909） | 91.22%（644/706） |
| `dataagent-web` | 100.00%（32/32） | 无生产分支 |

两个模块的 Maven 覆盖率门禁均通过。所有测试方法的中文 `@DisplayName` 策略检查通过。

## 端到端与消费端验证

Web 集成测试使用官方 MCP Java Client 连接随机端口的 `/rest/mcp`，并用本地 `HttpServer` 模拟
API Fabric，验证结果如下：

- MCP `initialize` 和 `tools/list` 成功，服务只发布 Tools 能力；
- JSON 工具调用成功，Path、Query、业务 Header 和展开 Body 分别进入正确位置；
- 同名 Header/Body 参数使用不同 Tool 输入键，并分别写入下游 Header 与 JSON Body；
- multipart 文件、普通 `RequestParam` 与文本 `RequestPart` 同时上传成功；
- 不存在的文件在下游请求前失败，模拟 API Fabric 调用次数保持为零。

安装最新 starter 构件后，对同级消费端 `/Users/tommy/projects/dataagent-mcp-test` 执行：

```text
mvn clean verify
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

消费端同时验证本地 Tool、API Fabric JSON、CSE JSON、API Fabric multipart 与 CSE multipart 调用。

## JavaDoc、JAR 与规范验证

主工程 `mvn javadoc:aggregate` 与消费端 `mvn javadoc:javadoc` 均为 `BUILD SUCCESS`。主工程保留
10 个、消费端保留 2 个既有默认构造器警告，没有 JavaDoc 错误。

主工程构建生成：

- `dataagent-mcp/target/dataagent-mcp-0.1.0-SNAPSHOT.jar`
- `dataagent-web/target/dataagent-web-0.1.0-SNAPSHOT.jar`

starter JAR 包含自动配置元数据、Tools 运行时、远程路由与 multipart 实现，不包含测试 class。

归档前逐一执行 `openspec validate <change> --strict`，4 个提案全部有效。随后按依赖顺序归档为：

- `2026-09-01-trim-mcp-starter`
- `2026-09-01-bind-tools-to-remote-endpoints`
- `2026-09-01-support-remote-file-upload`
- `2026-09-01-create-dataagent-parent-web`

归档后 `openspec list` 返回 `No active changes found`，并生成下列基线规范：

| 基线规范 | 需求数 | 严格校验 |
| --- | ---: | --- |
| `mcp-tool-runtime` | 15 | 通过 |
| `remote-tool-routing` | 12 | 通过 |
| `remote-file-upload` | 7 | 通过 |
| `dataagent-bff-application` | 6 | 通过 |

执行 `openspec validate --all --strict` 的最终结果为 4 项通过、0 项失败。两个较早提案在归档时仅有
“增量超过 10 条，建议拆分”的非阻断提示；提案已经完成且其需求属于同一能力边界，因此本次保持原提案
完整性，不为归档机械拆分历史记录。
