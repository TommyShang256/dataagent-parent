## 1. 配置与绑定契约

- [x] 1.1 在公共端点属性增加保持顺序的 `files` 映射，并增加默认 `100MB`、必须为正数的全局 `max-upload-file-size`；同步配置元数据并通过属性绑定测试覆盖默认值、有效值、null 防御性复制和非法上限
- [x] 1.2 扩展远程绑定期参数位置校验，要求 multipart 端点恰好一个非空文件 part、来源为已知 String 参数且不与 Path/Query/业务 Header 复用；通过目录级测试覆盖多文件、空名、未知参数、错误类型和位置冲突且确认不会发布部分目录
- [x] 1.3 为 multipart 剩余参数增加标量与标量集合类型校验，通过测试确认 String、primitive/wrapper、enum、时间类型、数组和明确泛型 Collection 可用，而对象、Map、原始/嵌套 Collection 与对象集合在启动期失败

## 2. 客户端无关的 multipart 请求计划

- [x] 2.1 将共享远程请求体建模为明确区分无 Body、JSON 和 multipart 的包内不可变 payload，保持现有构造器/方法参数不超过 5，并通过现有 JSON 请求测试确认未配置 `files` 的行为完全不变
- [x] 2.2 在 multipart 模式中把文件来源从普通字段排除，将其余实参数按原名转换为文本表单多值；通过聚焦测试覆盖标量、重复集合/数组、顺序、缺失/null 省略及 Header 与表单字段同名互不覆盖
- [x] 2.3 实现 filePath 非空、Path 语法、存在、普通文件、可读和大小上限校验，以及文件名和媒体类型探测回退；通过临时文件测试覆盖合法文件、目录、不存在、不可读、超限、符号链接允许和 `application/octet-stream`

## 3. API Fabric 与 CSE multipart 传输

- [x] 3.1 扩展 API Fabric handler，使用 WebClient multipart inserter 和文件 Resource 发送文件与普通字段且不预读完整文件；通过下游请求捕获验证 method、URI、Header、boundary、part 名、文件名、媒体类型、内容、重复字段、响应转换和失败映射
- [x] 3.2 扩展 CSE handler，使用 RestOperations multipart MultiValueMap/HttpEntity 和文件 Resource 发送相同语义；通过请求捕获验证完整 CSE URI 不改写、multipart 各 part、Header、响应转换、失败映射及客户端缺失仍在目录发布前失败
- [x] 3.3 增加资源生命周期与大文件行为验证，确认两个 handler 不构造文件大小级 byte[]、成功和失败请求后可关闭/删除临时文件，并记录校验后文件变化的竞争边界

## 4. 消费端与真实 MCP Client 调测

- [x] 4.1 在同级 `dataagent-mcp-test` 增加使用 `String filePath`、普通参数和代表性 Header/Query 的 API Fabric 与 CSE 上传代理工具及捕获测试，确认方法体不执行且固定工具目录、Schema 与审计行为正确
- [x] 4.2 扩展 starter 测试路径中的 OpenCode 真实 MCP client 端到端用例：创建临时文件，经 initialize、`tools/list` 和 `tools/call` 调用上传工具，断言 mock API Fabric 收到标准 multipart 文件与普通字段，并确认测试结束清理临时文件

## 5. 文档与最终门禁

- [x] 5.1 更新 README、配置示例和配置元数据，说明 `files` 映射、普通表单字段规则、100MB 默认上限、媒体类型回退、任意可读路径/BFF 沙箱责任、审计路径风险，以及多文件和 JSON part 暂不支持；通过文档属性与实现一致性检查
- [x] 5.2 输出完整中文调测与验证记录，执行 starter `clean verify`、JavaDoc、最新构件安装、同级消费端 `clean verify` 与 JavaDoc、严格 OpenSpec、生产字符串扫描、结构计数、`javap -p`、JAR、`git diff --check` 和暂存区检查，并将最新代码与材料加入 Git 暂存区但不提交

## 6. 测试质量强化

- [x] 6.1 为 starter 配置 JaCoCo `verify` 门禁，要求生产代码指令覆盖率与分支覆盖率均至少为 91%；基于覆盖率报告补齐缺失路径测试并确认干净构建在门禁下通过
- [x] 6.2 为主工程和同级消费端每个 JUnit 测试用例增加中文 `@DisplayName`，增加可持续的测试命名策略检查并确认能定位遗漏用例
- [x] 6.3 更新完整调测记录中的覆盖率明细，重跑 starter 与消费端全部门禁、严格 OpenSpec、JavaDoc、JAR、diff 和暂存区检查，并暂存最新材料但不提交
