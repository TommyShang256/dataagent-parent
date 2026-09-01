package ai.opencode.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 验证主工程与消费端的每个 JUnit 测试用例都具有明确的中文显示名称。
 *
 * @author beining.shang
 * @since 2026-09-01
 */
class TestDisplayNamePolicyTest {

    private static final Pattern TEST_METHOD = Pattern.compile(
            "(?s)@(?:Test|ParameterizedTest|RepeatedTest|TestFactory)\\b(?<declaration>.*?)"
                    + "(?<method>[A-Za-z_$][A-Za-z0-9_$]*)\\s*\\([^(){};]*\\)\\s*(?:throws [^{]+)?\\{");

    private static final Pattern DISPLAY_NAME = Pattern.compile("@DisplayName\\(\"(?<name>[^\"]+)\"\\)");

    private static final Pattern CHINESE_CHARACTER = Pattern.compile("\\p{IsHan}");

    @Test
    @DisplayName("所有 JUnit 测试用例都使用中文 DisplayName")
    void everyJUnitTestUsesChineseDisplayName() throws IOException {
        List<String> violations = new ArrayList<>();
        inspect(Path.of("src/test/java"), violations);
        inspect(Path.of("../dataagent-web/src/test/java"), violations);
        inspect(Path.of("../../dataagent-mcp-test/src/test/java"), violations);
        assertThat(violations)
                .as("JUnit test methods without a Chinese @DisplayName")
                .isEmpty();
    }

    private static void inspect(Path root, List<String> violations) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(file -> file.toString().endsWith(".java")).sorted().toList()) {
                inspect(path, Files.readString(path), violations);
            }
        }
    }

    private static void inspect(Path path, String source, List<String> violations) {
        Matcher testMethod = TEST_METHOD.matcher(source);
        while (testMethod.find()) {
            Matcher displayName = DISPLAY_NAME.matcher(testMethod.group("declaration"));
            if (!displayName.find() || !CHINESE_CHARACTER.matcher(displayName.group("name")).find()) {
                violations.add(path + "#" + testMethod.group("method"));
            }
        }
    }
}
