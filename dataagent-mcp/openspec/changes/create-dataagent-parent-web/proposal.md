## Why

当前 MCP starter 与面向用户的 Web/BFF 应用缺少统一的父工程和可运行集成入口，导致依赖版本、模块构建以及远程工具配置只能分别维护。现在需要建立 DataAgent 多模块工程，让 BFF 直接消费 MCP 模块并提供一套可启动、可验证的工具配置。

## What Changes

- **BREAKING**：将现有 `dataagent-mcp` Git 仓库整体迁入 `/Users/tommy/projects/DataAgentSelf/dataagent-mcp`，仓库工作路径随之改变。
- 在 `/Users/tommy/projects/DataAgentSelf` 创建 Maven 聚合父工程，统一 Java、Spring Boot、MCP SDK、Lombok、测试和构建插件版本，并聚合 `dataagent-mcp` 与 `dataagent-web`。
- 将 `dataagent-mcp` 调整为继承 DataAgent 父 POM，同时保持其 starter 构件坐标和公共能力。
- 新建与 MCP 同级的 `dataagent-web` Spring Boot BFF 模块，并显式依赖 `dataagent-mcp`。
- 在 BFF 中配置 MCP HTTP 服务及 API Fabric 远程工具示例，包括普通参数、业务 Header、JSON Body、文件与普通 `RequestParam`/`RequestPart` 组合上传场景。
- 增加 BFF 启动、工具发现、参数 Schema 和远程调用绑定测试，以及父工程统一构建与 JavaDoc 验证记录。

## Capabilities

### New Capabilities

- `dataagent-bff-application`：定义 DataAgent 多模块工程、可运行 BFF、MCP starter 集成和远程工具配置的外部行为。

### Modified Capabilities

无。

## Impact

- 目录与 Git：现有仓库从 `/Users/tommy/projects/dataagent-mcp` 迁移到 `/Users/tommy/projects/DataAgentSelf/dataagent-mcp`，新增父工程和 `dataagent-web` 模块。
- Maven：新增聚合父 POM；`dataagent-mcp` 的父 POM、版本管理和构建入口发生变化，但其 artifactId 与公开 Java API 保持不变。
- 运行时：新增 BFF Spring Boot 进程，通过 `/rest/mcp` 发布配置好的远程工具；实际调用依赖 API Fabric 地址与目标接口可达。
- 测试：父工程需覆盖模块级构建，BFF 测试使用 MockWebServer 或等价本地 HTTP 服务验证请求映射与 multipart 上传，不依赖公司环境。
- 文档：现有 OpenSpec、验证记录和本地调测文档中的绝对路径需要随迁移更新。
