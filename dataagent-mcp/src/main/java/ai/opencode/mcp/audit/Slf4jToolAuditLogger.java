package ai.opencode.mcp.audit;

import lombok.extern.slf4j.Slf4j;

/**
 * 使用 SLF4J 参数化日志记录完整的 MCP 工具审计事件。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
@Slf4j(topic = "ai.opencode.mcp.audit.tool")
public final class Slf4jToolAuditLogger implements ToolAuditLogger {

    /**
     * 工具审计专用 Logger 名称。
     */
    public static final String LOGGER_NAME = "ai.opencode.mcp.audit.tool";

    /**
     * 使用独立 SLF4J Logger 记录工具审计事件。
     *
     * @param event 待记录的审计事件
     *              返回值：无。
     */
    @Override
    public void record(ToolAuditEvent event) {
        String message = "MCP tool audit operation={} outcome={} tool={} type={} durationMs={} "
                + "caller={} skillId={} scriptId={} parentCallId={} traceId={} "
                + "arguments={} result={} errorType={}";
        Object[] arguments = new Object[]{
                event.operation(),
                event.outcome(),
                event.toolName(),
                event.type(),
                event.duration().toMillis(),
                event.source() == null ? null : event.source().caller(),
                event.source() == null ? null : event.source().skillId(),
                event.source() == null ? null : event.source().scriptId(),
                event.source() == null ? null : event.source().parentCallId(),
                event.source() == null ? null : event.source().traceId(),
                event.arguments(),
                event.result(),
                event.errorType()
        };
        if (event.outcome() == ToolAuditEvent.Outcome.SUCCESS) {
            log.info(message, arguments);
            return;
        }
        log.warn(message, arguments);
    }
}
