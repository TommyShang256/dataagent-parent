package ai.opencode.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Executable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 验证 starter 生产与测试字节码中的函数参数数量上限。
 *
 * @author beining.shang
 * @since 2026-09-01
 */
class ParameterCountPolicyTest {

  private static final int MAX_PARAMETERS = 5;

  @Test
  void methodsAndConstructorsHaveAtMostFiveParameters() throws Exception {
    List<String> violations = new ArrayList<>();
    inspect(Path.of("target/classes"), violations);
    inspect(Path.of("target/test-classes"), violations);

    assertThat(violations)
        .as("Methods and constructors with more than five parameters")
        .isEmpty();
  }

  private static void inspect(Path root, List<String> violations) throws IOException, ClassNotFoundException {
    if (!Files.isDirectory(root)) {
      return;
    }
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
        String className = root.relativize(file).toString()
            .replace(file.getFileSystem().getSeparator(), ".")
            .replaceFirst("\\.class$", "");
        inspect(Class.forName(className, false, ParameterCountPolicyTest.class.getClassLoader()), violations);
      }
    }
  }

  private static void inspect(Class<?> type, List<String> violations) {
    for (Executable constructor : type.getDeclaredConstructors()) {
      recordViolation(type, constructor, violations);
    }
    for (Executable method : type.getDeclaredMethods()) {
      recordViolation(type, method, violations);
    }
  }

  private static void recordViolation(Class<?> type, Executable executable, List<String> violations) {
    if (!executable.isSynthetic() && executable.getParameterCount() > MAX_PARAMETERS) {
      violations.add(type.getName() + "#" + executable.getName() + ": " + executable.getParameterCount());
    }
  }
}
