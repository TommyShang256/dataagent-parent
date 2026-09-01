package ai.opencode.mcp.audit;

import ai.opencode.mcp.annotation.Tool;
import ai.opencode.mcp.api.ToolCallSource;

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
 * @param target 工具审计目标
 * @param details 调用审计详情
 * @author beining.shang
 * @since 2026-08-31
 */
public record ToolAuditEvent(
    Instant timestamp,
    Operation operation,
    Outcome outcome,
    Target target,
    Details details) {

  /**
   * 获取工具名称。
   *
   * @return 工具名称
   */
  public String toolName() {
    return target.toolName;
  }

  /**
   * 获取工具执行类别。
   *
   * @return 工具执行类别
   */
  public Tool.Type type() {
    return target.type;
  }

  /**
   * 获取操作耗时。
   *
   * @return 操作耗时
   */
  public Duration duration() {
    return details.duration;
  }

  /**
   * 获取调用参数。
   *
   * @return 调用参数的不可变副本
   */
  public Map<String, Object> arguments() {
    return details.arguments;
  }

  /**
   * 获取调用结果。
   *
   * @return 调用结果
   */
  public Object result() {
    return details.result;
  }

  /**
   * 获取异常类型名称。
   *
   * @return 异常类型名称
   */
  public String errorType() {
    return details.errorType;
  }

  /**
   * 获取工具调用来源。
   *
   * @return 注册事件返回 {@code null}，调用事件返回有效来源
   */
  public ToolCallSource source() {
    return details.source;
  }

  /**
   * 标识审计事件对应的工具。
   */
  public static final class Target {

    private final String toolName;
    private final Tool.Type type;

    /**
     * 创建工具审计目标。
     *
     * @param toolName 工具名称
     * @param type 工具执行类别
     */
    public Target(String toolName, Tool.Type type) {
      this.toolName = toolName;
      this.type = type;
    }
  }

  /**
   * 保存一次工具操作的审计详情。
   */
  public static final class Details {

    private final Duration duration;
    private final Map<String, Object> arguments;
    private final Object result;
    private final String errorType;
    private final ToolCallSource source;

    /**
     * 创建审计详情，并对调用参数执行防御性复制。
     *
     * @param duration 操作耗时
     * @param arguments 调用参数
     * @param result 调用结果
     * @param errorType 异常类型名称
     * @param source 工具调用来源
     */
    public Details(
        Duration duration,
        Map<String, Object> arguments,
        Object result,
        String errorType,
        ToolCallSource source) {
      this.duration = duration;
      this.arguments = arguments == null
          ? null
          : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
      this.result = result;
      this.errorType = errorType;
      this.source = source;
    }

    /**
     * 获取异常类型名称。
     *
     * @return 异常类型名称
     */
    public String errorType() {
      return errorType;
    }
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
