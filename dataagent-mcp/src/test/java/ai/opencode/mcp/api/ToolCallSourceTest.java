package ai.opencode.mcp.api;

import ai.opencode.mcp.annotation.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证服务端绑定调用者与客户端审计来源链的解析规则。
 *
 * @author beining.shang
 * @since 2026-09-01
 */
class ToolCallSourceTest {

    @Test
    @DisplayName("Agent 入口不需要客户端调用来源")
    void agentEndpointDoesNotRequireClientSource() {
        ToolCallSource source = ToolCallSource.from(null);

        assertThat(source.caller()).isEqualTo(Tool.Caller.AGENT);
        assertThat(source.skillId()).isNull();
    }

    @Test
    @DisplayName("使用服务端 Script 调用者解析完整来源链")
    void parsesCompleteScriptSource() {
        ToolCallSource source = ToolCallSource.from(Map.of(
                ToolCallSource.SKILL_ID_META_KEY, "create-table",
                ToolCallSource.SCRIPT_ID_META_KEY, "create",
                ToolCallSource.PARENT_CALL_ID_META_KEY, "parent-1",
                ToolCallSource.TRACE_ID_META_KEY, "trace-1"), Tool.Caller.SCRIPT);

        assertThat(source).isEqualTo(new ToolCallSource(
                Tool.Caller.SCRIPT, "create-table", "create", "parent-1", "trace-1"));
    }

    @Test
    @DisplayName("拒绝客户端 caller 和非法来源字段")
    void rejectsClientCallerAndInvalidSourceValues() {
        assertThatThrownBy(() -> ToolCallSource.from(Map.of(ToolCallSource.CALLER_META_KEY, "script")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpoint", "must not");
        assertThatThrownBy(() -> ToolCallSource.from(Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("caller", "null");
        assertThatThrownBy(() -> ToolCallSource.from(Map.of(ToolCallSource.SKILL_ID_META_KEY, 3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank string");
        assertThatThrownBy(() -> ToolCallSource.from(Map.of(
                ToolCallSource.SKILL_ID_META_KEY, "skill"), Tool.Caller.SCRIPT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires", "trace");
        assertThatThrownBy(() -> ToolCallSource.from(Map.of(
                ToolCallSource.SKILL_ID_META_KEY, "forged")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Agent", "must not");
        assertThatThrownBy(() -> ToolCallSource.from(Map.of(
                ToolCallSource.PARENT_CALL_ID_META_KEY, "forged")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Agent", "must not");
        assertThatThrownBy(() -> ToolCallSource.from(Map.of(
                ToolCallSource.TRACE_ID_META_KEY, "forged")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Agent", "must not");
        assertThatThrownBy(() -> ToolCallSource.from(Map.of(ToolCallSource.TRACE_ID_META_KEY, " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank string");
        assertIncompleteScriptSource(Map.of(
                ToolCallSource.SCRIPT_ID_META_KEY, "script",
                ToolCallSource.PARENT_CALL_ID_META_KEY, "parent",
                ToolCallSource.TRACE_ID_META_KEY, "trace"));
        assertIncompleteScriptSource(Map.of(
                ToolCallSource.SKILL_ID_META_KEY, "skill",
                ToolCallSource.PARENT_CALL_ID_META_KEY, "parent",
                ToolCallSource.TRACE_ID_META_KEY, "trace"));
        assertIncompleteScriptSource(Map.of(
                ToolCallSource.SKILL_ID_META_KEY, "skill",
                ToolCallSource.SCRIPT_ID_META_KEY, "script",
                ToolCallSource.TRACE_ID_META_KEY, "trace"));
        assertIncompleteScriptSource(Map.of(
                ToolCallSource.SKILL_ID_META_KEY, "skill",
                ToolCallSource.SCRIPT_ID_META_KEY, "script",
                ToolCallSource.PARENT_CALL_ID_META_KEY, "parent"));
    }

    private static void assertIncompleteScriptSource(Map<String, Object> source) {
        assertThatThrownBy(() -> ToolCallSource.from(source, Tool.Caller.SCRIPT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires");
    }
}
