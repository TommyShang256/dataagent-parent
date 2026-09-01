package ai.opencode.mcp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.opencode.mcp.annotation.Tool;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 验证标准化工具注册信息的输入校验、不可变性和复制行为。
 *
 * @author beining.shang
 * @since 2026-09-01
 */
class ToolRegistrationTest {

    private static final ToolRegistration.Definition DEFINITION =
            new ToolRegistration.Definition(null, null, Map.of("type", "object"));

    private static final ToolRegistration.Behavior BEHAVIOR =
            new ToolRegistration.Behavior(true, false, true, false, Set.of(Tool.Caller.AGENT));

    private static final ToolInvoker INVOKER = arguments -> "ok";

    @Test
    @DisplayName("拒绝空名称和所有 null 注册组件")
    void rejectsBlankNameAndNullRegistrationComponents() {
        assertThatThrownBy(() -> registration(null, DEFINITION, INVOKER, Tool.Type.LOCAL, BEHAVIOR))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name");
        assertThatThrownBy(() -> registration(" ", DEFINITION, INVOKER, Tool.Type.LOCAL, BEHAVIOR))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name");
        assertThatThrownBy(() -> registration("tool", null, INVOKER, Tool.Type.LOCAL, BEHAVIOR))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("definition");
        assertThatThrownBy(() -> registration("tool", DEFINITION, null, Tool.Type.LOCAL, BEHAVIOR))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("invoker");
        assertThatThrownBy(() -> registration("tool", DEFINITION, INVOKER, null, BEHAVIOR))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("type");
        assertThatThrownBy(() -> registration("tool", DEFINITION, INVOKER, Tool.Type.LOCAL, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("behavior");
        assertThatThrownBy(() -> new ToolRegistration.Behavior(true, false, true, false, Set.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("allowedCallers");
        assertThatThrownBy(() -> new ToolRegistration.Behavior(true, false, true, false, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("allowedCallers");
        HashSet<Tool.Caller> callersWithNull = new HashSet<>();
        callersWithNull.add(null);
        assertThatThrownBy(() -> new ToolRegistration.Behavior(
                true, false, true, false, callersWithNull))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("null");
    }

    @Test
    @DisplayName("定义复制 Schema 且注册复制方法只替换目标字段")
    void defensivelyCopiesSchemaAndCopiesRegistrationFields() throws Exception {
        Map<String, Object> schema = new LinkedHashMap<>(Map.of("type", "object"));
        ToolRegistration.Definition definition = new ToolRegistration.Definition("title", "description", schema);
        schema.put("late", true);
        ToolRegistration registration = registration("tool", definition, INVOKER, Tool.Type.LOCAL, BEHAVIOR);

        assertThat(registration.title()).isEqualTo("title");
        assertThat(registration.description()).isEqualTo("description");
        assertThat(registration.inputSchema()).doesNotContainKey("late");
        assertThatThrownBy(() -> registration.inputSchema().put("new", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(registration.readOnly()).isTrue();
        assertThat(registration.destructive()).isFalse();
        assertThat(registration.idempotent()).isTrue();
        assertThat(registration.openWorld()).isFalse();
        assertThat(registration.allowedCallers()).containsExactly(Tool.Caller.AGENT);

        ToolInvoker replacement = arguments -> "replacement";
        assertThat(registration.withType(Tool.Type.CSE).type()).isEqualTo(Tool.Type.CSE);
        assertThat(registration.withInvoker(replacement).invoker().invoke(Map.of())).isEqualTo("replacement");
    }

    @Test
    @DisplayName("拒绝 null 输入 Schema")
    void rejectsNullInputSchema() {
        assertThatThrownBy(() -> new ToolRegistration.Definition(null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputSchema");
    }

    private static ToolRegistration registration(
            String name,
            ToolRegistration.Definition definition,
            ToolInvoker invoker,
            Tool.Type type,
            ToolRegistration.Behavior behavior) {
        return new ToolRegistration(name, definition, invoker, type, behavior);
    }
}
