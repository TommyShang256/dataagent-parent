# dataagent-mcp 项目协作记忆

本文件适用于当前仓库及其全部子目录。后续修改本项目时，除非用户明确给出新的相反要求，否则遵循以下约定。

## 语言与文档

- 所有 OpenSpec 提案、设计、任务、验证材料、README、代码注释和 JavaDoc 使用中文。
- 每个 Java 源文件的顶层类、接口、record、enum 或注解声明前必须有说明其职责的 JavaDoc，并包含：
  `@author beining.shang` 和精确到天的 `@since yyyy-MM-dd`。
- 所有 `public` 方法必须有 JavaDoc：包含函数说明、每个参数的 `@param` 和非 void 返回值的 `@return`。
  void 方法使用“返回值：无。”说明，不添加无效的 `@return` 标签。
- 注释解释业务边界、约束或不直观的原因，不复述显而易见的代码。

## Java 编码风格

- Lombok 版本固定为 1.18.40；在能够减少无逻辑样板代码且不隐藏重要行为时优先使用 Lombok。
- 生产代码不使用 `var`；测试代码也应尽量使用明确类型。
- 所有条件、循环及其他控制语句均使用 `{}`，即使语句体只有一行。
- 优先使用不可变集合、防御性复制、明确类型和启动期校验。
- 保持错误信息准确，至少包含能定位问题的类别、引用和参数；不要保留不可达分支或含糊措辞。
- 项目尚未上线，不为旧 API 保留兼容适配器、废弃入口或双轨实现，除非用户重新要求兼容。

## 结构设计准则

- 在代码整洁、准确和职责清晰的前提下，尽可能减少类与方法数量。
- 只有存在独立策略、公共扩展边界、共享职责或框架 SPI 要求时才新增或保留类型。
- 单字段包装、纯转发类、重复元数据和只调用另一个方法的无逻辑层应优先删除或内联。
- 不为了表面减少类数而混合 Schema、扫描、路由、请求执行、审计等不同职责。
- 枚举可以嵌入最相关的公共类型中；由配置或绑定过程解析出的状态，不开放为可能冲突的注解配置项。
- 公共 SPI 必须对应真实替换场景；没有消费场景的编程式注册 API 不保留。

## 当前 MCP 设计约束

- MCP 只聚焦 Tools，不实现 Resources、Prompts 或运行时目录修改。
- MCP HTTP 端点为 `/rest/mcp`。
- 工具只通过 `@Tool` 声明，`@Tool.name` 同时作为远程端点 `ref`。
- `Tool.Type` 表示绑定后的最终执行类别，由 scanner 和端点处理器自动确定，不作为 `@Tool` 参数手工配置。
- API Fabric 与 CSE 使用公共 `RemoteToolEndpointHandler` SPI，并保留可独立替换的默认实现。
- API Fabric 共享 `base-url`；CSE 保留完整 `cse://service-name/...` URI；二者均通过 `WebClient` 调用。
- Path 参数由 URI template 占位符自动识别；Query 和业务 Header 显式配置；排除这些参数后，其余参数按原名自动组成展开的 JSON Body。
- Header 配置只声明 `business`；其他允许的入站 Header 默认透传。透传 Header 不进入工具 Schema、arguments 或审计值。
- 请求 Header 直接使用不可变的多值 `Map<String, List<String>>` 传递，不额外声明只做包装的调用上下文类型。
- 当前只支持 JSON Body；multipart 文件上传保留为待办，不提前实现。

## 工作与验证方式

- 需求形成或改变设计时，先同步中文 OpenSpec 的 proposal、design、spec、tasks，再实现代码，确保材料与实现一致。
- 编辑文件使用补丁方式，保留用户已有修改，不覆盖无关内容。
- 重构后搜索并清理旧类型、旧术语、陈旧导入、构建产物和文档引用。
- 至少执行 starter 的 `clean verify` 和 JavaDoc；涉及公共 starter API 时，安装最新构件并执行同级
  `../dataagent-mcp-test` 的 `clean verify` 和 JavaDoc。
- 完成前执行严格 OpenSpec 校验、`git diff --check`、构建 JAR 检查，并确认没有未预期的能力或陈旧类。
- 结构收敛任务使用一致口径比较生产源码数、编译类数以及 `javap -p` 的构造器/方法数，不使用不一致的历史统计口径。
- 完成验证后将本仓库最新代码和材料加入 Git 暂存区；不得提交或推送，除非用户明确要求。
