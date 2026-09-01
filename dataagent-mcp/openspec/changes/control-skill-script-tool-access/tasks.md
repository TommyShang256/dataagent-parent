## 1. 提案收敛

- [x] 1.1 把方案改为 Agent 与 Script 双标准 MCP 入口，删除修改 Opencode、客户端 caller 注入和受信任 Runner 的设计
- [x] 1.2 明确入口绑定调用者、目录隔离、执行期授权、外置身份认证及 Script 审计字段边界
- [x] 1.3 明确配置命名空间使用框架中立的 `dataagent.mcp`，且不保留 `opencode.mcp` 兼容绑定

## 2. 服务端双入口

- [x] 2.1 增加 Script endpoint 配置，装配独立的 Agent/Script transport、Servlet registration 与 MCP Server
- [x] 2.2 注册表按 `allowedCallers` 发布到对应 Server，并实现跨 Server 失败回滚
- [x] 2.3 call handler 使用服务端绑定 caller，执行期重新校验后再调用 invoker
- [x] 2.4 将 starter 属性绑定、条件装配、配置元数据与 BFF `mcp-config.yml` 统一迁移到 `dataagent.mcp`，同步测试和文档

## 3. 来源与审计

- [x] 3.1 删除客户端 caller 解析，拒绝保留 caller `_meta`，并按绑定 caller 校验 Agent/Script 来源链
- [x] 3.2 保证来源字段不进入业务 arguments、透传 Header或远程请求，审计记录绑定 caller 与 Script 链路

## 4. 客户端与集成验证

- [x] 4.1 使用两个官方 MCP Java Client 验证隔离目录和四种合法调用组合
- [x] 4.2 验证错误 caller、来源链缺失和执行期越权均在本地/远程 invoker 前失败
- [x] 4.3 使用未修改的 Opencode MCP Client 验证 Agent endpoint 初始化、目录与调用成功
- [x] 4.4 输出包含端点、目录矩阵、标准请求、拒绝位置及测试数量的中文验证记录

## 5. Opencode 回退与质量门禁

- [x] 5.1 精确撤销 `/Users/tommy/projects/opencode` 中本需求产生的源码、测试、Schema、依赖和文档改动
- [x] 5.2 执行 DataAgent 父工程 `clean verify`、聚合 JavaDoc、覆盖率和 JAR 内容检查
- [x] 5.3 执行严格 OpenSpec 校验、`git diff --check`、陈旧术语搜索和两个仓库状态检查
- [x] 5.4 更新 README、配置元数据和协作约束，将 DataAgent 最新材料加入暂存区但不提交、不推送
