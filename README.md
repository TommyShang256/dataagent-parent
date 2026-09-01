# DataAgentSelf

DataAgentSelf 是聚合 MCP starter 与 BFF 应用的 Maven 多模块父工程。

## 模块

- `dataagent-mcp`：注解驱动的 MCP Tools starter。
- `dataagent-web`：依赖 MCP starter、发布远程工具的 Spring Boot BFF。
- `dataagent-runner`：不依赖 jq/curl/第三方包的单文件 Shell MCP Client，支持目录查询和调用前预检。

MCP Tool 可分别声明仅 Agent、仅 Skill Script 或二者均可调用。BFF 从同一 Tool 注册派生 `/rest/mcp` Agent
目录和 `/rest/mcp/script` Script 目录，并在标准 `tools/call` 上执行最终授权；Opencode 无需源码改造。

在父工程执行 `mvn clean verify` 可完成三个模块的编译、测试、覆盖率校验和打包。

Runner 的安装、Pod 环境变量、工具查询、调用、Header 与文件参数说明见
[`dataagent-runner/README.md`](dataagent-runner/README.md)。
