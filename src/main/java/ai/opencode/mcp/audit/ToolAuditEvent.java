package ai.opencode.mcp.audit;

import ai.opencode.mcp.annotation.Tool;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 完整的 MCP 工具审计事件，包含调用参数和结果。
 *
 * @param timestamp 事件时间
 * @param operation 审计操作
 * @param outcome 操作结果
 * @param toolName 工具名称
 * @param type 工具执行类别
 * @param duration 操作耗时
 * @param arguments 调用参数
 * @param result 调用结果
 * @param errorType 异常类型名称
 * @author beining.shang
 * @since 2026-08-31
 */
public record ToolAuditEvent(
    Instant timestamp,
    Operation operation,
    Outcome outcome,
    String toolName,
    Tool.Type type,
    Duration duration,
    Map<String, Object> arguments,
    Object result,
    String errorType) {

  /**
   * 创建审计事件，并对调用参数执行防御性复制。
   *
   * @param timestamp 事件时间
   * @param operation 审计操作
   * @param outcome 操作结果
   * @param toolName 工具名称
   * @param type 工具执行类别
   * @param duration 操作耗时
   * @param arguments 调用参数
   * @param result 调用结果
   * @param errorType 异常类型名称
   */
  public ToolAuditEvent {
    arguments = arguments == null
        ? null
        : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
  }

  /** 审计操作类别。 */
  public enum Operation {
    /** 工具注册。 */
    REGISTER,

    /** 工具调用。 */
    INVOKE
  }

  /** 审计操作结果。 */
  public enum Outcome {
    /** 操作成功。 */
    SUCCESS,

    /** 操作失败。 */
    FAILURE
  }
}
