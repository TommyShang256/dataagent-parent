package ai.opencode.dataagent.web.tool;

import ai.opencode.mcp.annotation.Tool;
import ai.opencode.mcp.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 声明由 API Fabric 远程端点实现的 BFF 示例工具。
 *
 * @author beining.shang
 * @since 2026-09-01
 */
@Component
public class ApiFabricTools {

    /**
     * 创建供 Spring 使用的工具目录实例。
     */
    ApiFabricTools() {
    }

    @Tool(
            name = "create_order",
            title = "Create order",
            description = "Create an order through API Fabric",
            idempotent = true)
    OrderResponse createOrder(
            @ToolParam(description = "Order identifier") String orderId,
            @ToolParam(description = "Whether to return verbose details") boolean verbose,
            @ToolParam(description = "Value of downstream business header A") String headerA,
            @ToolParam(name = "A", description = "Value of JSON body field A") String bodyA,
            @ToolParam(description = "Customer identifier", required = false) String customerId) {
        throw remoteOnly("create_order");
    }

    @Tool(
            name = "upload_table",
            title = "Upload table definition",
            description = "Upload a table DSL file and regular parameters through API Fabric")
    String uploadTable(
            @ToolParam(description = "Local path of the file to upload") String filePath,
            @ToolParam(description = "Catalog value sent as a regular request parameter") String catalog,
            @ToolParam(description = "Description sent as a text request part", required = false) String description) {
        throw remoteOnly("upload_table");
    }

    private static IllegalStateException remoteOnly(String toolName) {
        return new IllegalStateException("Remote proxy method must not execute: " + toolName);
    }

    /**
     * 表示创建订单远程接口的响应。
     *
     * @param id     订单标识
     * @param status 订单状态
     * @author beining.shang
     * @since 2026-09-01
     */
    public record OrderResponse(String id, String status) {
    }
}
