## Context

现有 `/Users/tommy/projects/dataagent-mcp` 是独立 Maven/Spring Boot starter 仓库，已实现 `/rest/mcp`、API Fabric/CSE 远程绑定及 multipart 文件上传，但没有消费该 starter 的正式 BFF 模块。详见 [proposal.md](proposal.md)。本次变更同时涉及 Git 根目录迁移、Maven 父子关系以及新应用集成，必须在保留现有提交历史和 MCP 构件坐标的前提下完成。

## Goals / Non-Goals

**Goals:**

- 形成单一 Git 工作树和单一 Maven reactor，根目录为 `/Users/tommy/projects/DataAgentSelf`。
- 保留 `dataagent-mcp` 的公共 API、artifactId 和版本，令现有外部消费者无需修改 Maven 坐标。
- 提供最小但完整的 BFF 应用，展示 API Fabric JSON 与 multipart 工具的推荐配置。
- 所有远程行为均可用本地模拟 HTTP 服务验证，父工程一次构建即可执行完整门禁。

**Non-Goals:**

- 不在 BFF 实现用户鉴权、文件沙箱、持久化、前端页面或业务编排。
- 不提供公司环境的真实 API Fabric 地址、凭据或 CSE 客户端实现。
- 不改变 `dataagent-mcp-test` 的定位；它继续作为仓库外部消费者验证工程存在。
- 不修改 MCP starter 已有的参数绑定和文件上传语义，除非集成测试暴露与现有规格不一致的缺陷。

## Decisions

### 1. 将现有仓库提升为 DataAgent 根仓库

把当前 Git 工作树目录重命名为 `/Users/tommy/projects/DataAgentSelf`，保留根目录 `.git`，再使用 Git 感知的移动将现有 MCP 代码、OpenSpec 和模块文档放入 `dataagent-mcp/`。根目录新增父 POM，`dataagent-web/` 与 MCP 同级。该目录名用于避开当前文件系统中已存在的独立 `/Users/tommy/projects/dataagent` 仓库。

选择该方案是为了让父 POM、两个模块及其变更历史处于同一仓库边界。备选方案是在新父目录中嵌套原 MCP Git 仓库，但父 POM和 Web 模块将无法随 MCP 代码原子提交，且 Maven 工程与版本控制边界不一致，因此不采用。

迁移完成后，项目级协作规则保留在 DataAgent 根目录；MCP 专属 OpenSpec 历史随模块移动。当前变更材料也随 `openspec/` 移入 MCP 模块，作为迁移决策和验证依据。

### 2. 根 POM同时承担聚合和版本管理

根 POM使用 `packaging=pom`，继承 Spring Boot starter parent，并声明两个 `<module>`。Java、MCP SDK、Lombok、JaCoCo 等共享版本置于根属性或 dependency management；模块只保留自身依赖和确有差异的插件配置。

相比仅使用 aggregator 而让每个模块继续独立继承 Spring Boot，统一父 POM能避免版本漂移并让 reactor 内依赖自动解析。`dataagent-mcp` 继续显式声明 `groupId=ai.opencode.mcp` 和现有版本；BFF 使用独立的 `ai.opencode.dataagent` 坐标，避免父工程命名影响 starter 发布坐标。

### 3. BFF 使用注解工具声明消费 MCP starter

`dataagent-web` 是标准 Spring Boot 可执行 JAR，依赖 `dataagent-mcp` 并通过受 Spring 扫描的 Bean 声明 `@Tool` 方法。配置类只承载工具元数据和远程接口签名，不引入本地业务转发 Controller；工具执行仍由 MCP starter 的 scanner、registry 和 API Fabric handler 完成。

JSON 示例覆盖 Path、Query、业务 Header 与 Body。Header/Body 同名参数使用现有位置前缀输入键策略，避免 MCP JSON Schema 属性冲突。multipart 示例覆盖文件 part、普通表单字段和非文件 RequestPart，文件只向 MCP 客户端暴露字符串路径。

备选方案是直接在 `application.yml` 描述整个工具目录，但当前 starter 的唯一声明方式是 `@Tool`，另建配置 DSL 会制造第二套模型，因此不采用。

### 4. 环境配置使用占位默认值并支持覆盖

仓库配置只提供适合本地模拟服务的地址占位符，通过 `DATAAGENT_API_FABRIC_BASE_URL` 等环境变量覆盖。测试使用动态端口注入，不依赖固定端口。任何认证信息仅可在部署环境注入，不写入版本库。

API Fabric 基础地址属于发布工具目录的必要条件：缺失或非法时由现有 starter 启动期校验失败。示例端点 ref 保持相对路径，使同一构件可指向不同环境。

MCP 配置独立存放于 classpath 根目录的 `mcp-config.yml`，包含 `opencode.mcp` 服务元数据、API Fabric 基础地址和远程端点映射。`application.yml` 仅保留应用自身配置，并通过 `spring.config.import=classpath:mcp-config.yml` 显式导入 MCP 配置。该边界让 BFF 的通用 Spring Boot 配置与 MCP 工具配置可以分别维护，同时继续使用 Spring Environment，因此环境变量、命令行参数和部署平台配置仍可按既有优先级覆盖文件默认值。

备选方案是使用 `spring.config.additional-location` 要求启动命令额外指定文件，但这会令默认启动方式无法自动获得必要的 MCP 配置，因此不采用。

### 5. 测试分层与覆盖率

BFF 单元测试验证工具注解、配置绑定和参数 Schema；集成测试以真实 MCP HTTP 请求驱动 BFF，再由本地模拟服务捕获 API Fabric 请求，从而验证客户端可观察的完整链路。所有测试使用中文 `@DisplayName`，并复用或扩展现有命名策略测试。

JaCoCo 在各含生产代码的模块执行门禁，指令和分支覆盖率最低值保持 `0.91`，高于规格要求的 90%。根 reactor 汇总构建结果，但不以聚合报告替代模块门禁，避免一个高覆盖模块掩盖另一个低覆盖模块。

## Risks / Trade-offs

- [目录迁移导致 IDE、脚本和绝对路径失效] → 全仓搜索 `/Users/tommy/projects/dataagent-mcp` 与相对父路径，更新文档和脚本，并在最终记录中列出新入口。
- [移动中的跨文件系统操作破坏 Git 元数据] → 在同一 `/Users/tommy/projects` 文件系统内先重命名根目录，立即核查 `git status` 和提交历史，再执行模块内 Git 移动。
- [BFF 示例配置被误当成生产配置] → 使用显式本地占位地址、注释和 README 说明，禁止提交凭据或真实环境主机名。
- [可执行 BFF 与 starter 依赖带来重复插件配置] → 父 POM只集中版本和公共插件；Spring Boot repackage 仅在 Web 模块启用。
- [端到端测试端口和异步启动造成不稳定] → 使用随机端口、确定性的模拟服务和有限超时，断言完整请求而非依赖时序休眠。

## Migration Plan

1. 确认当前工作树无未预期修改并记录当前分支、HEAD 与远端。
2. 将仓库根目录重命名为 `/Users/tommy/projects/DataAgentSelf`，验证 `.git` 历史仍可访问。
3. 创建 `dataagent-mcp/`，用 Git 移动现有 MCP 模块源码、POM、OpenSpec 和模块文档；将通用 `AGENTS.md` 保留或提升至根目录。
4. 创建根父 POM并调整 MCP 子 POM；先完成 MCP 模块原有 `clean verify` 和 JavaDoc，确保迁移未改变行为。
5. 创建 `dataagent-web`、工具配置、外部化配置、README 和测试，再执行父 reactor 全量验证。
6. 安装最新 MCP 构件并在 `/Users/tommy/projects/dataagent-mcp-test` 执行消费者验证。
7. 执行严格 OpenSpec、JAR 内容、`javap -p`、覆盖率、`git diff --check` 与路径残留检查，更新完整验证记录并暂存全部变更。

若迁移阶段失败，在未产生新提交前将目录恢复为 `/Users/tommy/projects/dataagent-mcp`，并将 Git 移动恢复到原路径；不使用破坏性 reset 覆盖用户数据。
