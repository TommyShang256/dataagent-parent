package ai.opencode.mcp.audit;

import static org.assertj.core.api.Assertions.assertThat;

import ai.opencode.mcp.annotation.Tool;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 验证生产代码的运行时字符串统一使用英文。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
class LoggingLanguageTest {

    private static final Pattern STRING_LITERAL = Pattern.compile(
            "\"\"\".*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"", Pattern.DOTALL);
    private static final Pattern CHINESE = Pattern.compile("\\p{IsHan}");

    @Test
    @DisplayName("生产运行时字符串不包含中文文本")
    void productionRuntimeStringsDoNotContainChineseText() throws IOException {
        List<String> violations = new ArrayList<>();
        List<Path> sourceRoots = List.of(
                Path.of("src/main/java"),
                Path.of("../dataagent-web/src/main/java"),
                Path.of("../../dataagent-mcp-test/src/main/java"));
        for (Path sourceRoot : sourceRoots) {
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            try (Stream<Path> sourceFiles = Files.walk(sourceRoot)) {
                for (Path sourceFile : sourceFiles.filter(path -> path.toString().endsWith(".java")).toList()) {
                    Matcher matcher = STRING_LITERAL.matcher(Files.readString(sourceFile));
                    while (matcher.find()) {
                        if (CHINESE.matcher(matcher.group()).find()) {
                            violations.add(sourceFile + ": " + matcher.group());
                        }
                    }
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("审计日志使用英文模板")
    void auditLoggerFormatsEnglishTemplate() {
        Logger logger = (Logger) LoggerFactory.getLogger(Slf4jToolAuditLogger.LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ToolAuditEvent event = new ToolAuditEvent(
                    Instant.EPOCH,
                    ToolAuditEvent.Operation.INVOKE,
                    ToolAuditEvent.Outcome.SUCCESS,
                    new ToolAuditEvent.Target("echo", Tool.Type.LOCAL),
                    new ToolAuditEvent.Details(
                            Duration.ZERO, Map.of("message", "hello"), "hello", null, null));
            new Slf4jToolAuditLogger().record(event);

            assertThat(appender.list).singleElement()
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .asString()
                    .doesNotContainPattern(CHINESE);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
