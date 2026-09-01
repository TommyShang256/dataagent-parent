# DataAgentSelf

DataAgentSelf 是聚合 MCP starter 与 BFF 应用的 Maven 多模块父工程。

## 模块

- `dataagent-mcp`：注解驱动的 MCP Tools starter。
- `dataagent-web`：依赖 MCP starter、发布远程工具的 Spring Boot BFF。

在父工程执行 `mvn clean verify` 可完成两个模块的编译、测试、覆盖率校验和打包。
