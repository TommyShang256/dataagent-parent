# MCP 运行稳定性修整验证

验证日期：2026-10-08。

## 修整范围

- API Fabric 与 CSE 的动态 Query 名称和值分别编码，避免分隔符改变请求含义；固定模板中已有的编码保持原样。
- scanner 在所有远程处理器执行前校验必填参数和 primitive 的 null，并与本地调用共享校验逻辑；保留远程业务参数原值和 Header 透传。
- 扫描阶段拒绝重复工具参数名，避免 Schema 覆盖和调用参数混淆。
- 原生 MCP 结果的 `isError=true` 记录为失败审计，不伪造异常类型。
- 上传文件必须使用绝对路径；API Fabric 基础地址必须包含有效主机，且不包含 Query 或 Fragment。
- 同步中文 README；按用户要求未处理 OpenSpec。

校验范围是必填参数是否存在及 primitive 是否为 null，不增加完整的运行时 JSON Schema 校验器。

## 自动化验证

使用本机 IntelliJ IDEA 自带 Maven：

```shell
'/Applications/IntelliJ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn' \
  -pl dataagent-mcp -am test \
  -Dtest=McpToolScannerTest,RemoteToolEndpointHandlerTest,McpToolRegistryTest \
  -Dsurefire.failIfNoSpecifiedTests=false

'/Applications/IntelliJ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn' \
  clean verify install javadoc:javadoc
```

- 定向测试：37 项，失败、错误、跳过均为 0。
- 完整 starter 测试：88 项，失败、错误、跳过均为 0。
- 完整 Web 测试：18 项，失败、错误、跳过均为 0。
- 真实 Opencode MCP 客户端端到端测试及 Web 双入口、Runner 集成测试均通过。
- starter 指令覆盖率：95.52%；分支覆盖率：91.11%，均达到 91% 门槛。
- Web 指令覆盖率：100%；生产代码没有分支计数。
- 父工程及两个模块构件已安装到本地 Maven 仓库；两个模块 JavaDoc 生成成功。
- JavaDoc 仍有既有默认构造器缺少注释等警告，本轮未调整无关声明。

## 构件与变更检查

- `git diff --check` 通过。
- starter JAR 的 45 个编译类与 `target/classes` 完全一致，未残留额外编译类。
- JAR 内 Runner 和自动配置导入文件与源码资源完全一致。
- 未新增 Resources、Prompts、Completions、运行时目录修改或 CSE 内置客户端实现。
- 本轮源码、测试、README 与验证记录加入暂存区；未提交或推送。

约定的同级目录 `/Users/tommy/projects/dataagent-mcp-test` 不存在，因此无法执行该独立消费工程的验证。
本仓库的 `dataagent-web` 消费模块已使用本轮 starter 完成完整验证。
