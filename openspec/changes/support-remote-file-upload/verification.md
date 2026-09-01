# 远程文件上传调测与验证记录

## 验证范围

本记录覆盖 API Fabric 与 CSE 的单文件 multipart 代理能力：Tool 使用字符串 `filePath`，端点通过
`files` 把下游文件 part 名映射到该参数；剩余简单参数生成可由 `@RequestParam` 接收的文本表单字段。
验证同时覆盖现有 Path、Query、业务 Header、入站 Header 透传、JSON 端点兼容性、响应转换、审计和
最多 5 个编译后参数约束。

明确不在本次范围内的能力为多文件、JSON `@RequestPart` DTO、对象表单字段、根目录白名单和异步上传。

## 实现契约

代表性配置：

```yaml
opencode:
  mcp:
    max-upload-file-size: 100MB
    api-fabric:
      base-url: https://api-fabric.example.com/v1
      endpoints:
        create_table:
          method: POST
          path-template: /v1/createTable
          files:
            dsl: filePath
```

对应 Tool 与下游接口：

```java
@Tool(name = "create_table")
String createTable(String filePath, String catalog, Boolean overwrite) {
    throw new AssertionError("Remote proxy tool method body must not execute");
}

void createTable(
        @RequestPart("dsl") MultipartFile file,
        @RequestParam("catalog") String catalog,
        @RequestParam(value = "overwrite", required = false) Boolean overwrite) {
}
```

运行期使用路径末段作为文件名，探测不到媒体类型时使用 `application/octet-stream`。路径必须非空、
语法有效、存在、为可读普通文件并在配置大小上限内。检查允许符号链接且不做根目录限制，文件系统隔离由
BFF 负责。文件通过 `FileSystemResource` 交给 Spring 消息写出器，不在生产代码中读取为 byte 数组。

## starter 聚焦测试

`McpFabricPropertiesTest` 覆盖：

- `max-upload-file-size` 默认 `100MB` 及 `8MB` 绑定；
- 非正数与 null 拒绝；
- `files` 的 YAML 绑定、null 归一化及防御性复制。

`RemoteToolEndpointHandlerTest` 覆盖：

- 文件 part 多项、空名、未知参数、非 String 来源以及与 Path、Query、业务 Header 同源冲突；
- String、primitive/wrapper、enum、时间、数组和标量 Collection；
- 对象、Map、原始 Collection、嵌套 Collection 与对象集合启动期拒绝；
- API Fabric 真实 multipart boundary、文件名、媒体类型、文件内容、普通字段和重复字段；
- CSE multipart MultiValueMap/HttpEntity、完整 `cse://` URI、文件 Resource 和响应转换；
- 缺失、null、空集合省略，以及数组重复字段；
- 路径缺失、非法、不存在、目录、不可读、超限、符号链接允许和媒体类型回退；
- 成功及失败后临时文件可删除，现有 JSON 请求测试全部保持通过。

聚焦结果：

```text
mvn -Dtest=McpFabricPropertiesTest,RemoteToolEndpointHandlerTest test
McpFabricPropertiesTest: Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
RemoteToolEndpointHandlerTest: Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

mvn -Dtest=RemoteToolEndpointHandlerTest test
Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Finished at: 2026-09-01T12:04:55+08:00
```

## OpenCode 标准 MCP Client 端到端调测

测试使用 `/Users/tommy/projects/opencode` 的 V2 MCP client，通过远程配置连接随机端口的当前 MCP Server。
客户端依次完成 initialize、`tools/list`、JSON `create_order` 的 `tools/call` 和 multipart
`upload_table` 的 `tools/call`。上传参数为 JUnit 临时文件绝对路径和普通 `description` 参数。

mock API Fabric 实际收到：

```http
POST /api/tables
X-Trace-Id: opencode-e2e
Content-Type: multipart/form-data; boundary=...

Content-Disposition: form-data; name="dsl"; filename="table.dsl"
Content-Type: application/octet-stream

create table demo

Content-Disposition: form-data; name="description"

opencode upload
```

服务端审计确认 `upload_table` 类型为 `API_FABRIC`、调用成功、arguments 只包含 filePath 与 description，
结果为 `uploaded`。临时文件在 MCP client 和下游请求完成后成功删除。

```text
mvn -Dtest=ApiFabricOpenCodeE2eTest test
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Finished at: 2026-09-01T12:01:35+08:00
```

首次扩展端到端夹具时，订单 mock JSON 少一个闭合花括号，导致旧 JSON 调用的响应转换失败，而上传调用已成功。
修正夹具后两个调用同时通过；生产代码没有针对该测试失败做兼容修改。

## 同级消费端验证

`../dataagent-mcp-test` 增加 `upload_table_api` 和 `upload_table_cse`，固定目录由 3 个扩展为 5 个工具。
两个工具都使用 `String filePath`、普通 description、Path、Query 和业务 Header；测试确认代理方法体不执行、
Schema 中 filePath 仍为字符串，并分别捕获 WebClient 与 RestOperations 的 multipart 语义。

首次消费端测试使用了本地 Maven 仓库中的旧 starter，因此 `files` 尚未生效，请求仍为 JSON。随后从 starter
目录安装最新构件并执行干净构建，结果通过：

```text
mvn clean verify
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 5.196 s
Finished at: 2026-09-01T14:09:36+08:00

mvn javadoc:javadoc
BUILD SUCCESS
Total time: 1.919 s
Finished at: 2026-09-01T14:09:47+08:00
```

消费端 JavaDoc 保留既有 2 个默认构造器警告，没有新增错误。

## 全量门禁

```text
mvn clean verify
Tests run: 62, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 5.796 s
Finished at: 2026-09-01T14:09:16+08:00

mvn javadoc:javadoc
BUILD SUCCESS
10 warnings
Total time: 2.461 s
Finished at: 2026-09-01T14:09:47+08:00

openspec validate support-remote-file-upload --strict
Change 'support-remote-file-upload' is valid

git diff --check
passed
```

全量 starter 构建中的审计异常栈来自既有“审计失败不改变注册或业务结果”的故障注入测试，最终没有测试失败。
生产字符串约束由 `LoggingLanguageTest` 在全量构建中通过，新增异常消息和字段均为英文。

## 结构、JAR 与暂存验证

用户确认删除临时示例后，已移除 `src/main/java/ai/opencode/mcp/registry/test.java` 并从干净目录重新构建。
最终结构与基线使用一致口径：

```text
生产 Java 源文件数：17
target/classes 生产 class 数：43
```

新增的 3 个生产 class 均是共享请求计划所需的包内嵌套不可变类型：`HeaderMapping`、`RequestPayload` 和
`FilePart`。`javap -p` 检查绑定器、两个端点处理器和配置属性，构造器及方法参数均不超过 5；
`ParameterCountPolicyTest` 同时在 starter 和消费端通过。

最终 JAR 为 `target/dataagent-mcp-0.1.0-SNAPSHOT.jar`，包含 multipart 绑定与两个传输处理器，且不包含
已删除的 `registry/test.class` 或 `ApiFabricOpenCodeE2eTest` 等测试 class。最新构件已在
2026-09-01T14:09:25+08:00 安装到本地 Maven 仓库，消费端随后执行干净验证。

```text
git diff --check
passed

git diff --cached --check
passed
```

本次最新代码、OpenSpec 与验证材料已加入 Git 暂存区，未提交、未推送。同级 `dataagent-mcp-test` 不是 Git
仓库，因此其消费端夹具和文档修改无法在该目录中加入暂存区。
