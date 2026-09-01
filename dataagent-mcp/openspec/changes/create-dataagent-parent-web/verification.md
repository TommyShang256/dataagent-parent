# DataAgent 父工程与 BFF 实施验证记录

## 迁移前基线

- 记录日期：2026-09-01
- MCP 仓库：`/Users/tommy/projects/dataagent-mcp`
- 分支：`main`
- HEAD：`de98dbaaf1d7f8b287b8ada8872d3fe0f86d692f`
- 远端：`origin=https://github.com/TommyShang256/dataagent-mcp.git`
- 工作树：除本变更 `openspec/changes/create-dataagent-parent-web/` 外无修改。
- 外部消费者：`/Users/tommy/projects/dataagent-mcp-test/pom.xml` 存在。

## 目录冲突核查

尝试把 MCP 仓库重命名为 `/Users/tommy/projects/DataAgent` 时，发现该路径在当前文件系统上解析为已存在的 `/Users/tommy/projects/dataagent`。后者是独立 Git 仓库，HEAD 为 `8fb9e5a47acbd5ac32be602c2eda524237dbe6cf`。操作实际形成了嵌套目录 `/Users/tommy/projects/dataagent/dataagent-mcp`，未修改外层仓库的已跟踪文件。

发现冲突后已立即将 MCP 仓库恢复到 `/Users/tommy/projects/dataagent-mcp`。恢复验证结果：

- MCP Git 根、HEAD 与迁移前一致；
- 现有 DataAgent 仓库 Git 根、HEAD 与发现时一致；
- 两个仓库均无此次尝试产生的已跟踪修改；
- 后续目录方案需要用户确认后继续。

用户确认不接入已有 `/Users/tommy/projects/dataagent`，改用无冲突的新父工程路径 `/Users/tommy/projects/DataAgentSelf`，后续实施按该路径继续。

## 最终目录与 Maven 模型

最终 Git 根目录为 `/Users/tommy/projects/DataAgentSelf`，原 HEAD 与远端历史保留。Maven reactor 顺序为：

```text
DataAgent Parent [pom]
DataAgent MCP    [jar]
DataAgent Web    [jar]
```

模块坐标：

- 父工程：`ai.opencode.dataagent:dataagent-parent:0.1.0-SNAPSHOT`
- MCP：`ai.opencode.mcp:dataagent-mcp:0.1.0-SNAPSHOT`
- BFF：`ai.opencode.dataagent:dataagent-web:0.1.0-SNAPSHOT`

本机 shell 的 `PATH` 中没有独立 `mvn`，全部 Maven 验证使用 IntelliJ IDEA 内置 Maven：

```text
/Applications/IntelliJ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn
```

`mvn help:effective-pom -DskipTests` 成功解析三个 reactor 项目。MCP 子模块继承根父 POM，构件坐标保持不变；BFF 显式依赖该 MCP 构件。

## BFF 工具与标准 MCP 验证

`dataagent-web` 配置并发布两个 API Fabric 工具：

- `create_order`：POST JSON 请求，覆盖 Path、Query、业务 Header、展开 Body，并验证下游 Header `A` 与 Body 字段 `A` 使用独立 MCP 输入值；
- `upload_table`：POST multipart 请求，`filePath` 映射文件 part `dsl`，同时发送普通 `catalog` 和文本 `description` part。

`DataAgentMcpIntegrationTest` 使用官方 MCP Java Client 的 streamable HTTP transport 连接随机端口 `/rest/mcp`，真实执行初始化、`tools/list` 和 `tools/call`。本地 JDK `HttpServer` 捕获 API Fabric 请求，验证结果如下：

- MCP Server 信息为 `dataagent-web:0.1.0`；
- 工具目录包含 `create_order` 与 `upload_table`；
- JSON 请求的方法、Path、Query、Header 和 Body 均符合配置，Header/Body 同名值未覆盖；
- multipart 请求包含文件名、文件内容、普通 `RequestParam` 和文本 `RequestPart`；
- 不存在的文件在远程请求前失败，本地 API Fabric 调用次数保持为零；
- 空 API Fabric `base-url` 在工具目录发布前以明确英文错误拒绝启动。

Web 模块测试结果：

```text
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

所有新增测试均具有中文 `@DisplayName`。MCP 中的命名策略测试已扩展为同时扫描 MCP、Web 和外部消费者；生产运行时语言策略也同时扫描三个工程。

## 覆盖率

父工程最终 `clean verify` 后从 JaCoCo CSV 汇总：

| 模块 | 指令覆盖 | 分支覆盖 | 行覆盖 |
| --- | ---: | ---: | ---: |
| dataagent-mcp | 4698 / 4909，95.70% | 644 / 706，91.22% | 957 / 999，95.80% |
| dataagent-web | 32 / 32，100.00% | 0 / 0，无可执行分支，门禁通过 | 10 / 10，100.00% |

两个模块的 JaCoCo 指令与分支最低门禁均配置为 `0.91`，最终 `jacoco:check` 全部通过。Web 生产类没有编译出的条件分支，因此分支集合为空，JaCoCo 按满足门禁处理。

## 构建与 JavaDoc

父工程执行：

```text
mvn clean verify
DataAgent Parent SUCCESS
DataAgent MCP    SUCCESS
DataAgent Web    SUCCESS
BUILD SUCCESS

mvn javadoc:aggregate
BUILD SUCCESS
```

MCP 共运行 78 个测试，0 失败、0 错误、1 个环境条件跳过；Web 共运行 11 个测试，0 失败、0 错误、0 跳过。聚合 JavaDoc 成功，保留 MCP 既有的 10 个默认构造器警告，未引入新的 Web JavaDoc 警告。

执行 `mvn -DskipTests install` 后，外部消费者 `/Users/tommy/projects/dataagent-mcp-test` 验证结果：

```text
mvn clean verify
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

mvn javadoc:javadoc
BUILD SUCCESS
```

外部消费者 JavaDoc 保留既有的 2 个默认构造器警告。

## 构件与结构检查

- MCP 生产源码：17 个；生产 class：43 个；与迁移前口径一致。
- Web 生产源码：2 个；生产 class：3 个。
- MCP JAR 不包含测试类或 `target/test-classes`。
- Web 可执行 JAR 包含 `DataAgentWebApplication`、`ApiFabricTools` 及内嵌 `dataagent-mcp-0.1.0-SNAPSHOT.jar`。
- `javap -p` 确认 MCP 关键类型 `RemoteToolInvokerBinder`、`ApiFabricToolEndpointHandler`、`CseToolEndpointHandler` 的签名没有非预期变化。
- `javap -p` 确认 Web 仅包含启动入口、两个远程工具方法和响应 record，没有额外业务转发层。
- 旧绝对路径只保留在迁移提案、回滚方案、基线记录和历史 JVM 崩溃快照中；有效源码与构建配置均使用新目录或正确相对路径。
- 配置中只包含本地回环占位地址，不包含令牌、密码、API Key 或真实公司环境地址。

## 最终质量门禁

```text
openspec validate create-dataagent-parent-web --strict
Change 'create-dataagent-parent-web' is valid

git diff --check
通过，无空白错误
```
