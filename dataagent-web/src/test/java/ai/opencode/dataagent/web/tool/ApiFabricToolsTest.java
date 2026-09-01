package ai.opencode.dataagent.web.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import ai.opencode.mcp.annotation.Tool;
import ai.opencode.mcp.annotation.ToolParam;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 验证 API Fabric 示例工具声明及远程代理保护。
 *
 * @author beining.shang
 * @since 2026-09-01
 */
class ApiFabricToolsTest {

    @Test
    @DisplayName("订单工具声明同名 Header 与 Body 的独立输入")
    void orderToolDeclaresIndependentHeaderAndBodyInputs() throws Exception {
        Method method = ApiFabricTools.class.getDeclaredMethod(
                "createOrder", String.class, boolean.class, String.class, String.class, String.class);
        Tool tool = method.getAnnotation(Tool.class);
        ToolParam body = method.getParameters()[3].getAnnotation(ToolParam.class);

        assertThat(tool.name()).isEqualTo("create_order");
        assertThat(tool.idempotent()).isTrue();
        assertThat(body.name()).isEqualTo("A");
        assertThat(Arrays.stream(method.getParameters()).map(parameter -> parameter.getName()).toList())
                .contains("headerA", "bodyA");
    }

    @Test
    @DisplayName("上传工具只使用字符串文件路径并保留普通参数")
    void uploadToolUsesStringPathAndRegularParameters() throws Exception {
        Method method = ApiFabricTools.class.getDeclaredMethod(
                "uploadTable", String.class, String.class, String.class);

        assertThat(method.getAnnotation(Tool.class).name()).isEqualTo("upload_table");
        assertThat(Set.of(method.getAnnotation(Tool.class).allowedCallers()))
                .containsExactlyInAnyOrder(Tool.Caller.AGENT, Tool.Caller.SCRIPT);
        assertThat(Arrays.stream(method.getParameterTypes())).containsOnly(String.class);
        assertThat(Arrays.stream(method.getParameters()).map(parameter -> parameter.getName()).toList())
                .containsExactly("filePath", "catalog", "description");
    }

    @Test
    @DisplayName("远程工具方法体不会承担本地业务执行")
    void remoteToolBodiesRejectLocalExecution() {
        ApiFabricTools tools = new ApiFabricTools();
        assertThatThrownBy(() -> tools.createOrder("O-1", true, "header", "body", "C-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Remote proxy method must not execute: create_order");
        assertThatThrownBy(() -> tools.uploadTable("/tmp/table.dsl", "demo", "description"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Remote proxy method must not execute: upload_table");
        assertThatThrownBy(() -> tools.validateTable("demo"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Remote proxy method must not execute: validate_table");
    }

    @Test
    @DisplayName("校验工具仅允许 Script 调用")
    void validateToolAllowsOnlyScriptCaller() throws Exception {
        Method method = ApiFabricTools.class.getDeclaredMethod("validateTable", String.class);
        assertThat(method.getAnnotation(Tool.class).allowedCallers())
                .containsExactly(Tool.Caller.SCRIPT);
    }

    @Test
    @DisplayName("订单响应记录保留远程字段")
    void orderResponseKeepsRemoteFields() {
        ApiFabricTools.OrderResponse response = new ApiFabricTools.OrderResponse("O-1", "created");
        assertThat(response.id()).isEqualTo("O-1");
        assertThat(response.status()).isEqualTo("created");
    }
}
