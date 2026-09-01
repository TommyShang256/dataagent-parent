## 1. 迁移前核查与目录重组

- [x] 1.1 记录当前分支、HEAD、远端、工作树状态及仓库外 `dataagent-mcp-test` 路径，确认没有未预期修改后再迁移，并以核查记录验证基线完整。
- [x] 1.2 将仓库根目录迁移为 `/Users/tommy/projects/DataAgentSelf`，保留根 `.git` 与项目级协作配置，执行 `git rev-parse --show-toplevel` 和 `git log -1` 验证仓库历史未丢失。
- [x] 1.3 创建 `dataagent-mcp/` 并使用 Git 感知的移动迁入现有 MCP 源码、POM、OpenSpec 和模块文档，执行 `git status --short` 验证文件被识别为移动且没有遗漏生产或测试源码。

## 2. 父工程与 MCP 子模块

- [x] 2.1 创建 DataAgent 根父 POM，配置 `packaging=pom`、`dataagent-mcp` 与 `dataagent-web` 模块及统一 Java、Spring Boot、MCP SDK、Lombok、测试和 JaCoCo 版本，执行 `mvn help:effective-pom` 验证父模型可解析。
- [x] 2.2 调整 MCP POM继承 DataAgent 父 POM并移除重复版本管理，执行 `mvn -pl dataagent-mcp clean verify` 验证原有测试和 0.91 指令/分支覆盖率门禁继续通过。
- [x] 2.3 检查 MCP 构件坐标、JAR 内容和公共类型签名，使用 `mvn help:evaluate`、`jar tf` 与 `javap -p` 验证 artifactId、版本、生产类及公共 API没有非预期变化。

## 3. dataagent-web BFF 模块

- [x] 3.1 创建 `dataagent-web` Maven 子模块、Spring Boot 启动类和可执行 JAR 配置，显式依赖 `ai.opencode.mcp:dataagent-mcp:0.1.0-SNAPSHOT`，执行模块上下文启动测试验证依赖和自动配置生效。
- [x] 3.2 添加外部化 MCP/API Fabric 配置，以本地占位地址和环境变量覆盖基础地址且不包含真实凭据，执行配置绑定测试验证默认值、覆盖值及缺失必要配置的启动失败信息。
- [x] 3.3 声明 API Fabric JSON 示例工具，覆盖 Path、Query、业务 Header、展开 Body 及 Header/Body 同名参数，执行工具扫描和 Schema 测试验证工具目录与位置化输入键。
- [x] 3.4 声明 API Fabric multipart 示例工具，覆盖 `filePath` 文件 part、普通 `RequestParam` 和非文件 `RequestPart`，执行工具扫描和 Schema 测试验证文件只暴露字符串路径且普通参数均可输入。
- [x] 3.5 为 BFF 新增中文 README，说明启动命令、环境变量、MCP 端点、示例 `tools/list`/`tools/call` 请求、文件路径边界及远程接口映射，并通过人工核对和文档命令复制执行验证示例完整。
- [x] 3.6 将全部 `opencode.mcp` 配置迁移到独立 `mcp-config.yml`，令 `application.yml` 只显式导入该文件并保留应用基础配置，增加配置边界测试并执行 Web 模块验证。

## 4. MCP 到 API Fabric 集成测试

- [x] 4.1 增加基于随机端口 BFF 和本地模拟 HTTP 服务的 MCP 初始化及 `tools/list` 测试，断言 `/rest/mcp` 协议响应、工具名称和输入 Schema。
- [x] 4.2 增加 JSON 工具端到端测试，从标准 `tools/call` 请求验证 HTTP 方法、Path、Query、业务 Header、展开 Body、同名 Header/Body 值隔离及 MCP 返回结果。
- [x] 4.3 增加 multipart 工具端到端测试，使用临时文件验证文件 part、普通表单字段、文本或结构化 `RequestPart` 的名称、内容类型和内容，并验证不可读文件不会发出远程请求。
- [x] 4.4 为所有新增测试添加中文 `@DisplayName`，扩展命名策略测试覆盖两个模块，并执行扫描测试验证不存在未命名或非中文命名的测试方法。
- [x] 4.5 配置并补齐 BFF 测试至指令和分支覆盖率均至少 0.91，执行 `mvn -pl dataagent-web clean verify` 并核对 JaCoCo XML/HTML 报告中的精确比例。

## 5. 全量验证与交付记录

- [x] 5.1 在 DataAgent 根目录执行 `mvn clean verify` 和 `mvn javadoc:aggregate`，验证两个模块 reactor 构建、测试、覆盖率、打包及中文 JavaDoc 门禁全部通过。
- [x] 5.2 执行 `mvn -DskipTests install` 后在 `/Users/tommy/projects/dataagent-mcp-test` 运行 `mvn clean verify` 与 `mvn javadoc:javadoc`，验证仓库外消费者仍可使用最新 MCP 构件。
- [x] 5.3 全仓搜索旧绝对路径、旧父 POM和陈旧模块引用并更新必要文档，执行 `rg` 复核只保留迁移说明中有意出现的旧路径。
- [x] 5.4 执行 `openspec validate create-dataagent-parent-web --strict`、`git diff --check`、JAR 测试类排除检查、生产源码/类数量统计和关键类型 `javap -p`，将完整命令、输出摘要、覆盖率及目录结构写入中文验证记录。
- [x] 5.5 核查最终 `git status`、差异范围和敏感配置，将 DataAgent 根仓库全部预期代码与材料加入暂存区但不提交、不推送，并验证暂存差异无凭据或无关文件。
