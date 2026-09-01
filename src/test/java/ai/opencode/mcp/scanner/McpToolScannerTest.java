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
    void rejectsOptionalPrimitiveDuringDiscovery() {
        assertThatThrownBy(() -> scanner.scan(new InvalidTools()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot be optional");
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

    static class InvalidTools {
        @Tool
        void invalid(@ToolParam(required = false) int value) {
        }
    }
}
