package ai.opencode.mcp.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.opencode.mcp.annotation.Tool;
import ai.opencode.mcp.annotation.ToolParam;
import ai.opencode.mcp.api.ToolRegistration;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * 验证注解工具扫描、参数转换和本地调用行为。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
class McpToolScannerTest {

    private final McpToolScanner scanner = new McpToolScanner(
            new DefaultListableBeanFactory(), new ObjectMapper().findAndRegisterModules(), List.of());

    @Test
    @DisplayName("区分缺失值与 null 并创建空 Optional")
    void distinguishesMissingAndNullAndCreatesEmptyOptionals() throws Exception {
        ToolRegistration registration = tool("parameters");

        assertThatThrownBy(() -> registration.invoker().invoke(Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requiredText");

        Result result = (Result) registration.invoker().invoke(nullableArguments());
        assertThat(result).isEqualTo(new Result(null, null, Optional.empty(), OptionalInt.empty(), 7));
        NumericResult numeric = (NumericResult) tool("numeric_optionals").invoker().invoke(Map.of());
        assertThat(numeric).isEqualTo(new NumericResult(OptionalLong.empty(), OptionalDouble.empty()));
    }

    @Test
    @DisplayName("使用配置的 ObjectMapper 转换已提供参数")
    void convertsPresentValuesThroughConfiguredMapper() throws Exception {
        ToolRegistration registration = tool("parameters");
        Result result = (Result) registration.invoker().invoke(Map.of(
                "optionalText", "present",
                "requiredText", "required",
                "optional", "value",
                "optionalInt", 3,
                "primitive", 9));

        assertThat(result).isEqualTo(new Result(
                "present", "required", Optional.of("value"), OptionalInt.of(3), 9));
        NumericResult numeric = (NumericResult) tool("numeric_optionals").invoker().invoke(
                Map.of("optionalLong", 4, "optionalDouble", 5.5));
        assertThat(numeric).isEqualTo(new NumericResult(OptionalLong.of(4), OptionalDouble.of(5.5)));
    }

    @Test
    @DisplayName("拒绝显式 null 和非法 primitive 参数")
    void rejectsExplicitNullAndInvalidPrimitiveValues() {
        ToolRegistration registration = tool("parameters");
        var nullPrimitive = nullableArguments();
        nullPrimitive.put("primitive", null);

        assertThatThrownBy(() -> registration.invoker().invoke(nullPrimitive))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("primitive", "null");
        assertThatThrownBy(() -> registration.invoker().invoke(Map.of(
                "requiredText", "ok", "primitive", "not-a-number")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("扫描阶段拒绝可选 primitive 参数")
    void rejectsOptionalPrimitiveDuringDiscovery() {
        assertThatThrownBy(() -> scanner.scan(new InvalidTools()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot be optional");
    }

    @Test
    @DisplayName("传播工具方法抛出的受检异常和 Error")
    void propagatesCheckedExceptionsAndErrorsFromToolMethods() {
        List<ToolRegistration> tools = scanner.scan(new FailingTools());
        ToolRegistration checked = tools.stream().filter(tool -> tool.name().equals("checked")).findFirst().orElseThrow();
        ToolRegistration error = tools.stream().filter(tool -> tool.name().equals("error")).findFirst().orElseThrow();

        assertThatThrownBy(() -> checked.invoker().invoke(Map.of()))
                .isInstanceOf(Exception.class)
                .hasMessage("checked failure");
        assertThatThrownBy(() -> error.invoker().invoke(Map.of()))
                .isInstanceOf(AssertionError.class)
                .hasMessage("error failure");
    }

    @Test
    @DisplayName("ToolParam 显式名称同时用于 Schema 和调用参数")
    void usesExplicitToolParameterNameForSchemaAndInvocation() throws Exception {
        ToolRegistration registration = scanner.scan(new RenamedTools()).getFirst();
        assertThat(registration.inputSchema().toString()).contains("external");
        assertThat(registration.invoker().invoke(Map.of("external", "value"))).isEqualTo("value");
    }

    private static java.util.LinkedHashMap<String, Object> nullableArguments() {
        var arguments = new java.util.LinkedHashMap<String, Object>();
        arguments.put("requiredText", null);
        arguments.put("optional", null);
        arguments.put("optionalInt", null);
        arguments.put("primitive", 7);
        return arguments;
    }

    private ToolRegistration tool(String name) {
        return scanner.scan(new ParameterTools()).stream()
                .filter(registration -> registration.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    record Result(
            String optionalText,
            String requiredText,
            Optional<String> optional,
            OptionalInt optionalInt,
            int primitive) {
    }

    record NumericResult(OptionalLong optionalLong, OptionalDouble optionalDouble) {
    }

    static class ParameterTools {
        @Tool
        Result parameters(
                @ToolParam(required = false) String optionalText,
                String requiredText,
                Optional<String> optional,
                OptionalInt optionalInt,
                int primitive) {
            return new Result(optionalText, requiredText, optional, optionalInt, primitive);
        }

        @Tool(name = "numeric_optionals")
        NumericResult numericOptionals(OptionalLong optionalLong, OptionalDouble optionalDouble) {
            return new NumericResult(optionalLong, optionalDouble);
        }
    }

    static class FailingTools {

        @Tool(name = "checked")
        String checked() throws Exception {
            throw new Exception("checked failure");
        }

        @Tool(name = "error")
        String error() {
            throw new AssertionError("error failure");
        }
    }

    static class RenamedTools {

        @Tool(title = "命名工具", description = "验证显式参数名称")
        String renamed(@ToolParam(name = "external") String internal) {
            return internal;
        }
    }

    static class InvalidTools {
        @Tool
        void invalid(@ToolParam(required = false) int value) {
        }
    }
}
