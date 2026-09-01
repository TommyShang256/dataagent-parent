package ai.opencode.mcp.api;

import ai.opencode.mcp.annotation.Tool;

import java.util.Map;

/**
 * 保存由服务端入口确定的调用者与 MCP Client 提供的审计来源链。
 *
 * @param caller       有效调用者类别
 * @param skillId      Skill 标识
 * @param scriptId     Script 标识
 * @param parentCallId 父调用标识
 * @param traceId      链路追踪标识
 * @author beining.shang
 * @since 2026-09-01
 */
public record ToolCallSource(
        Tool.Caller caller,
        String skillId,
        String scriptId,
        String parentCallId,
        String traceId) {

    /**
     * Tool 目录中允许调用者的元数据键。
     */
    public static final String ALLOWED_CALLERS_META_KEY = "ai.opencode.dataagent/allowed-callers";

    /**
     * 客户端不得声明的保留调用者元数据键。
     */
    public static final String CALLER_META_KEY = "ai.opencode.dataagent/caller";

    /**
     * Skill 标识的请求元数据键。
     */
    public static final String SKILL_ID_META_KEY = "ai.opencode.dataagent/skill-id";

    /**
     * Script 标识的请求元数据键。
     */
    public static final String SCRIPT_ID_META_KEY = "ai.opencode.dataagent/script-id";

    /**
     * 父调用标识的请求元数据键。
     */
    public static final String PARENT_CALL_ID_META_KEY = "ai.opencode.dataagent/parent-call-id";

    /**
     * 链路追踪标识的请求元数据键。
     */
    public static final String TRACE_ID_META_KEY = "ai.opencode.dataagent/trace-id";

    /**
     * 使用 Agent 入口语义解析 MCP request `_meta`。
     *
     * @param meta MCP request 元数据
     * @return 解析后的不可变调用来源
     */
    public static ToolCallSource from(Map<String, Object> meta) {
        return from(meta, Tool.Caller.AGENT);
    }

    /**
     * 使用服务端入口绑定的调用者解析 MCP request `_meta`。
     *
     * @param meta MCP request 元数据
     * @param caller 服务端入口绑定的调用者
     * @return 解析后的不可变调用来源
     */
    public static ToolCallSource from(Map<String, Object> meta, Tool.Caller caller) {
        Map<String, Object> safeMeta = meta == null ? Map.of() : meta;
        if (caller == null) {
            throw new IllegalArgumentException("MCP endpoint caller must not be null");
        }
        if (safeMeta.containsKey(CALLER_META_KEY)) {
            throw new IllegalArgumentException("MCP tool caller is determined by the endpoint and must not be declared");
        }
        ToolCallSource source = new ToolCallSource(
                caller,
                text(safeMeta.get(SKILL_ID_META_KEY)),
                text(safeMeta.get(SCRIPT_ID_META_KEY)),
                text(safeMeta.get(PARENT_CALL_ID_META_KEY)),
                text(safeMeta.get(TRACE_ID_META_KEY)));
        source.validate();
        return source;
    }

    private void validate() {
        boolean hasChain = skillId != null || scriptId != null || parentCallId != null || traceId != null;
        if (caller == Tool.Caller.AGENT && hasChain) {
            throw new IllegalArgumentException("MCP Agent caller must not declare Script source metadata");
        }
        if (caller == Tool.Caller.SCRIPT
                && (skillId == null || scriptId == null || parentCallId == null || traceId == null)) {
            throw new IllegalArgumentException("MCP Script caller requires skill, script, parent call, and trace metadata");
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("MCP tool source metadata values must be non-blank strings");
        }
        return text;
    }
}
