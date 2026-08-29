package ai.opencode.mcp.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.opencode.mcp.annotation.Tool;
import ai.opencode.mcp.annotation.ToolParam;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class McpToolScannerTest {

  private final McpToolScanner scanner = new McpToolScanner(
      new DefaultListableBeanFactory(), new ObjectMapper().findAndRegisterModules());

  @Test
  void distinguishesMissingAndNullAndCreatesEmptyOptionals() throws Exception {
    var registration = scanner.scan(new ParameterTools()).getFirst();

    assertThatThrownBy(() -> registration.invoker().invoke(Map.of()))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requiredText");

    var result = (Result) registration.invoker().invoke(nullableArguments());
    assertThat(result).isEqualTo(new Result(null, null, Optional.empty(), OptionalInt.empty(),
        OptionalLong.empty(), OptionalDouble.empty(), 7));
  }

  @Test
  void convertsPresentValuesThroughConfiguredMapper() throws Exception {
    var registration = scanner.scan(new ParameterTools()).getFirst();
    var result = (Result) registration.invoker().invoke(Map.of(
        "optionalText", "present",
        "requiredText", "required",
        "optional", "value",
        "optionalInt", 3,
        "optionalLong", 4,
        "optionalDouble", 5.5,
        "primitive", 9));

    assertThat(result).isEqualTo(new Result("present", "required", Optional.of("value"), OptionalInt.of(3),
        OptionalLong.of(4), OptionalDouble.of(5.5), 9));
  }

  @Test
  void rejectsExplicitNullAndInvalidPrimitiveValues() {
    var registration = scanner.scan(new ParameterTools()).getFirst();
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
    arguments.put("optionalLong", null);
    arguments.put("optionalDouble", null);
    arguments.put("primitive", 7);
    return arguments;
  }

  record Result(
      String optionalText,
      String requiredText,
      Optional<String> optional,
      OptionalInt optionalInt,
      OptionalLong optionalLong,
      OptionalDouble optionalDouble,
      int primitive) {}

  static class ParameterTools {
    @Tool
    Result parameters(
        @ToolParam(required = false) String optionalText,
        String requiredText,
        Optional<String> optional,
        OptionalInt optionalInt,
        OptionalLong optionalLong,
        OptionalDouble optionalDouble,
        int primitive) {
      return new Result(optionalText, requiredText, optional, optionalInt, optionalLong, optionalDouble, primitive);
    }
  }

  static class InvalidTools {
    @Tool
    void invalid(@ToolParam(required = false) int value) {}
  }
}
