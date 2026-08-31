# DataAgent MCP

面向 Spring Boot 应用的注解驱动 MCP Server starter。项目直接使用官方 MCP Java SDK，不依赖 Spring AI；
只提供 `tools/list` 与 `tools/call`，不发布 Resources、Prompts、Completions 或运行时目录修改能力。
工具目录在应用启动时完成扫描、校验和发布，之后保持固定。

## 版本与依赖

- Java 21
- Spring Boot 3.4.13
- MCP Java SDK 2.0.1
- Lombok 1.18.40
- Spring MVC/Tomcat 服务端
- Spring WebFlux `WebClient` 下游客户端

starter 使用 `mcp-core` 与 `mcp-json-jackson2`，避免便捷聚合依赖选择 Jackson 3。

```xml
<dependency>
  <groupId>ai.opencode.mcp</groupId>
  <artifactId>dataagent-mcp</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

消费应用必须保留编译器 `-parameters` 元数据，或为参数显式设置 `@ToolParam(name = "...")`。

## 定义工具

未绑定远程端点的注解方法仍在本地执行：

```java
@Component
public class OrderTools {

  @Tool(name = "echo", description = "返回输入消息", readOnly = true)
  public String echo(String message) {
    return message;
  }
}
```

`@Tool.name` 同时是远程端点的 `ref`。一个 ref 只要出现在 API Fabric 或 CSE 配置中，方法签名仍负责
生成 Agent 可见的 JSON Schema，但方法体不会执行；`tools/call` 改为请求配置的下游端点。

## API Fabric 与 CSE 配置

```yaml
opencode:
  mcp:
    enabled: true
    endpoint: /rest/mcp
    server-name: dataagent-mcp
    server-version: 0.1.0
    request-timeout: 5m
    max-request-size: 16MB
    api-fabric:
      base-url: https://api-fabric.example.com/v1
      endpoints:
        create_order:
          method: POST
          path-template: /tenants/{tenantId}/orders
          query:
            dry_run: dryRun
          headers:
            business:
              X-Biz-Mode: bizMode
    cse:
      endpoints:
        reserve_inventory:
          method: POST
          uri-template: cse://inventory-service/warehouses/{warehouseId}/reservations
          query:
            validate_only: validateOnly
          headers:
            business:
              X-Biz-Mode: bizMode
```

配置规则是确定的：

1. URI 模板中的 `{tenantId}` 自动消费同名工具参数作为 Path，不配置 Path 映射，也不支持重命名。
2. `query` 使用“下游参数名: 工具参数名”，集合值生成重复 Query 项。
3. `headers.business` 使用“下游 Header 名: 工具参数名”，其参数继续出现在 Schema 和审计 arguments 中。
4. 排除 Path、Query、业务 Header 参数后，其余已提供的工具参数按原名组成 JSON Object Body。可选参数缺失时省略，显式 null 保留；没有剩余参数时不发送 Body。
5. 参数位置不按 HTTP method 推断。因此 GET 存在剩余参数时也会携带 JSON Body；需要无 Body 的 GET 时，应让模板、Query 或业务 Header 消费全部参数。

API Fabric 将公共 `base-url` 与 `path-template` 组合。CSE 的完整 `cse://service-name/...` URI 在模板展开后原样交给客户端，starter 不改写 scheme、不做服务发现。

## 普通入站 Header 默认透传

当前 `tools/call` HTTP 请求中的普通 Header 会按原名称和全部值复制到下游，无需配置透传列表。
这些 Header 不进入工具 Schema、arguments、端点选择或审计值；每次调用只使用当前请求的 Header，不缓存到 session，
也不使用 ThreadLocal。因此 BFF 必须在每次 `tools/call` 请求中携带所需 Header。

业务 Header 与入站 Header 同名时不区分大小写，工具参数生成的业务 Header 优先。以下系统 Header 始终排除：
`Host`、`Content-Length`、`Connection`、`Transfer-Encoding`、`Upgrade`、`Keep-Alive`、`TE`、`Trailer`、
`Accept`、`Content-Type`、`Mcp-Session-Id`、`Last-Event-ID`。包含 CR 或 LF 的 Header 值会被拒绝。

业务 Header 属于工具 arguments，默认审计实现会记录其值。敏感业务 Header 应由应用提供的 `ToolAuditLogger`
执行脱敏和保留策略；普通透传 Header 的值不会进入审计事件。

## 自定义 CSE WebClient

默认 HTTP connector 通常不识别 `cse` scheme。CSE 部署必须替换公开扩展点 `RemoteToolWebClientProvider`，
返回安装了对应 `ClientHttpConnector`、filter、认证或观测能力的 `WebClient`：

```java
@Bean
RemoteToolWebClientProvider remoteClients(WebClient cseClient, WebClient fabricClient) {
  return origin -> origin == ToolOrigin.Kind.SERVER_COMB ? cseClient : fabricClient;
}
```

调用沿用同步 MCP Server 模式，并以 `opencode.mcp.request-timeout` 作为阻塞等待上限。非 2xx 状态、connector
失败、超时、URI 构建和返回值转换失败都会成为 `isError=true` 的 MCP 工具结果，应用和固定目录保持可用。

## 启动校验

发布任何工具前会一次性校验完整绑定集合，包括：API Fabric/CSE 重复 ref、没有注解工具的 ref、非法 method
或 URI、模板占位符缺少同名参数、Query/业务 Header 引用未知参数、参数位置冲突、重复下游名称，以及业务
Header 使用系统排除名称。失败时不会发布部分工具目录。

通用的 `RemoteToolClient` 注册方式继续保留，适用于应用自行完成发现、Schema 和执行的远程系统；它与基于
注解签名的配置端点绑定相互独立。

## JSON Schema

Schema 生成器基于配置的 Jackson 反序列化模型，支持常用标量、枚举与 `@JsonValue`、Java time、UUID/URI、
数组与集合、Map、Optional、record/bean、继承、嵌套泛型和递归 `$defs`。根工具参数保持展开，普通对象默认
`additionalProperties: false`。缺失键与显式 JSON null 分别处理；primitive 不接受 null。

## 文件上传待办

当前版本只支持 JSON Object Body，不实现 `multipart/form-data` 或文件上传。后续设计需整体覆盖文件与表单字段、
Content-Type 和 boundary、单文件及总大小限制、流式传输、取消和异常时的临时资源清理，不能在现有 Body 规则上
零散增加特殊分支。

## 构建

```shell
mvn clean verify
```

默认 MCP 地址为 `http://localhost:8080/rest/mcp`。
