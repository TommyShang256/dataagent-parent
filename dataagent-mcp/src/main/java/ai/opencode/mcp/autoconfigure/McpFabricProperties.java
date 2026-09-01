package ai.opencode.mcp.autoconfigure;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * 承载 MCP starter 及远程端点的类型安全配置。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
@ConfigurationProperties("dataagent.mcp")
@Getter
@Setter
public class McpFabricProperties {

    /**
     * MCP starter 是否启用。
     * <p>
     * -- GETTER --
     * 获取 MCP starter 是否启用。
     *
     * @return 启用时返回 {@code true}
     * <p>
     * -- SETTER --
     * 设置 MCP starter 是否启用。
     * @param enabled 是否启用
     * 返回值：无。
     */
    private boolean enabled = true;

    /**
     * MCP HTTP 端点路径。
     * <p>
     * -- GETTER --
     * 获取 MCP HTTP 端点路径。
     *
     * @return MCP HTTP 端点路径
     * <p>
     * -- SETTER --
     * 设置 MCP HTTP 端点路径。
     * @param endpoint MCP HTTP 端点路径
     * 返回值：无。
     */
    private String endpoint = "/rest/mcp";

    /**
     * Script MCP HTTP 端点路径。
     * <p>
     * -- GETTER --
     * 获取 Script MCP HTTP 端点路径。
     *
     * @return Script MCP HTTP 端点路径
     * <p>
     * -- SETTER --
     * 设置 Script MCP HTTP 端点路径。
     * @param scriptEndpoint Script MCP HTTP 端点路径
     * 返回值：无。
     */
    private String scriptEndpoint = "/rest/mcp/script";

    /**
     * MCP Server 名称。
     * <p>
     * -- GETTER --
     * 获取 MCP Server 名称。
     *
     * @return MCP Server 名称
     * <p>
     * -- SETTER --
     * 设置 MCP Server 名称。
     * @param serverName MCP Server 名称
     * 返回值：无。
     */
    private String serverName = "dataagent-mcp";

    /**
     * MCP Server 版本。
     * <p>
     * -- GETTER --
     * 获取 MCP Server 版本。
     *
     * @return MCP Server 版本
     * <p>
     * -- SETTER --
     * 设置 MCP Server 版本。
     * @param serverVersion MCP Server 版本
     * 返回值：无。
     */
    private String serverVersion = "0.1.0";

    /**
     * MCP 请求及远程工具调用超时时间。
     * <p>
     * -- GETTER --
     * 获取请求超时时间。
     *
     * @return 请求超时时间
     * <p>
     * -- SETTER --
     * 设置请求超时时间。
     * @param requestTimeout 请求超时时间
     * 返回值：无。
     */
    private Duration requestTimeout = Duration.ofMinutes(5);

    /**
     * MCP 流式连接保活间隔。
     * <p>
     * -- GETTER --
     * 获取流式连接保活间隔。
     *
     * @return 保活间隔；{@code null} 表示使用 SDK 默认行为
     * <p>
     * -- SETTER --
     * 设置流式连接保活间隔。
     * @param keepAlive 保活间隔
     * 返回值：无。
     */
    private Duration keepAlive;

    /**
     * 单次 MCP 请求的最大大小。
     * <p>
     * -- GETTER --
     * 获取单次 MCP 请求的最大大小。
     *
     * @return 最大请求大小
     * <p>
     * -- SETTER --
     * 设置单次 MCP 请求的最大大小。
     * @param maxRequestSize 最大请求大小
     * 返回值：无。
     */
    private DataSize maxRequestSize = DataSize.ofMegabytes(16);

    /**
     * 单个远程上传文件的最大大小。
     * <p>
     * -- GETTER --
     * 获取单个远程上传文件的最大大小。
     *
     * @return 最大上传文件大小
     */
    private DataSize maxUploadFileSize = DataSize.ofMegabytes(100);

    /**
     * API Fabric 配置。
     * <p>
     * -- GETTER --
     * 获取 API Fabric 配置。
     *
     * @return API Fabric 配置
     */
    private ApiFabric apiFabric = new ApiFabric();

    /**
     * CSE 配置。
     * <p>
     * -- GETTER --
     * 获取 CSE 配置。
     *
     * @return CSE 配置
     */
    private Cse cse = new Cse();

    /**
     * 设置 API Fabric 配置，并将 {@code null} 归一化为空配置。
     *
     * @param apiFabric API Fabric 配置
     *                  返回值：无。
     */
    public void setApiFabric(ApiFabric apiFabric) {
        this.apiFabric = apiFabric == null ? new ApiFabric() : apiFabric;
    }

    /**
     * 设置 CSE 配置，并将 {@code null} 归一化为空配置。
     *
     * @param cse CSE 配置
     *            返回值：无。
     */
    public void setCse(Cse cse) {
        this.cse = cse == null ? new Cse() : cse;
    }

    /**
     * 设置单个远程上传文件的最大大小。
     *
     * @param maxUploadFileSize 最大上传文件大小，必须为正数
     *                          返回值：无。
     */
    public void setMaxUploadFileSize(DataSize maxUploadFileSize) {
        if (maxUploadFileSize == null || maxUploadFileSize.toBytes() <= 0) {
            throw new IllegalArgumentException("max-upload-file-size must be greater than zero");
        }
        this.maxUploadFileSize = maxUploadFileSize;
    }

    /**
     * API Fabric 共享基础地址及端点目录配置。
     */
    @Getter
    @Setter
    public static final class ApiFabric {

        /**
         * API Fabric 公共基础 URL。
         * <p>
         * -- GETTER --
         * 获取 API Fabric 公共基础 URL。
         *
         * @return API Fabric 公共基础 URL
         * <p>
         * -- SETTER --
         * 设置 API Fabric 公共基础 URL。
         * @param baseUrl API Fabric 公共基础 URL
         * 返回值：无。
         */
        private String baseUrl;

        /**
         * 按工具引用索引的 API Fabric 端点。
         * <p>
         * -- GETTER --
         * 获取 API Fabric 端点映射。
         *
         * @return 保持配置顺序的端点映射
         */
        private Map<String, ApiFabricEndpoint> endpoints = new LinkedHashMap<>();

        /**
         * 设置 API Fabric 端点，并执行空值归一化和防御性复制。
         *
         * @param endpoints 按工具引用索引的端点映射
         *                  返回值：无。
         */
        public void setEndpoints(Map<String, ApiFabricEndpoint> endpoints) {
            this.endpoints = endpoints == null ? new LinkedHashMap<>() : new LinkedHashMap<>(endpoints);
        }
    }

    /**
     * CSE 端点目录配置。
     */
    @Getter
    @Setter
    public static final class Cse {

        /**
         * 按工具引用索引的 CSE 端点。
         * <p>
         * -- GETTER --
         * 获取 CSE 端点映射。
         *
         * @return 保持配置顺序的端点映射
         */
        private Map<String, CseEndpoint> endpoints = new LinkedHashMap<>();

        /**
         * 设置 CSE 端点，并执行空值归一化和防御性复制。
         *
         * @param endpoints 按工具引用索引的端点映射
         *                  返回值：无。
         */
        public void setEndpoints(Map<String, CseEndpoint> endpoints) {
            this.endpoints = endpoints == null ? new LinkedHashMap<>() : new LinkedHashMap<>(endpoints);
        }
    }

    /**
     * API Fabric 与 CSE 端点共用的请求映射配置。
     */
    @Getter
    @Setter
    public abstract static class Endpoint {

        /**
         * 下游 HTTP 方法。
         * <p>
         * -- GETTER --
         * 获取下游 HTTP 方法。
         *
         * @return HTTP 方法名称
         * <p>
         * -- SETTER --
         * 设置下游 HTTP 方法。
         * @param method HTTP 方法名称
         * 返回值：无。
         */
        private String method;

        /**
         * Query 下游名称到工具参数名称的映射。
         * <p>
         * -- GETTER --
         * 获取 Query 参数映射。
         *
         * @return 保持配置顺序的 Query 参数映射
         */
        private Map<String, String> query = new LinkedHashMap<>();

        /**
         * 文件 part 下游名称到字符串工具参数名称的映射。
         * <p>
         * -- GETTER --
         * 获取文件 part 映射。
         *
         * @return 保持配置顺序的文件 part 映射
         */
        private Map<String, String> files = new LinkedHashMap<>();

        /**
         * Header 映射配置。
         * <p>
         * -- GETTER --
         * 获取 Header 映射配置。
         *
         * @return Header 映射配置
         */
        private Headers headers = new Headers();

        /**
         * 设置 Query 参数映射，并执行空值归一化和防御性复制。
         *
         * @param query 下游名称到工具参数名称的映射
         *              返回值：无。
         */
        public void setQuery(Map<String, String> query) {
            this.query = query == null ? new LinkedHashMap<>() : new LinkedHashMap<>(query);
        }

        /**
         * 设置文件 part 映射，并执行空值归一化和防御性复制。
         *
         * @param files 下游文件 part 名称到字符串工具参数名称的映射
         *              返回值：无。
         */
        public void setFiles(Map<String, String> files) {
            this.files = files == null ? new LinkedHashMap<>() : new LinkedHashMap<>(files);
        }

        /**
         * 设置 Header 映射配置，并将 {@code null} 归一化为空配置。
         *
         * @param headers Header 映射配置
         *                返回值：无。
         */
        public void setHeaders(Headers headers) {
            this.headers = headers == null ? new Headers() : headers;
        }
    }

    /**
     * API Fabric 单端点配置。
     */
    @Getter
    @Setter
    public static final class ApiFabricEndpoint extends Endpoint {

        /**
         * API Fabric 相对路径模板。
         * <p>
         * -- GETTER --
         * 获取 API Fabric 相对路径模板。
         *
         * @return 相对路径模板
         * <p>
         * -- SETTER --
         * 设置 API Fabric 相对路径模板。
         * @param pathTemplate 相对路径模板
         * 返回值：无。
         */
        private String pathTemplate;
    }

    /**
     * CSE 单端点配置。
     */
    @Getter
    @Setter
    public static final class CseEndpoint extends Endpoint {

        /**
         * 完整 CSE URI 模板。
         * <p>
         * -- GETTER --
         * 获取完整 CSE URI 模板。
         *
         * @return 完整 CSE URI 模板
         * <p>
         * -- SETTER --
         * 设置完整 CSE URI 模板。
         * @param uriTemplate 完整 CSE URI 模板
         * 返回值：无。
         */
        private String uriTemplate;
    }

    /**
     * 端点 Header 参数映射配置。
     */
    @Getter
    @Setter
    public static final class Headers {

        /**
         * 业务 Header 下游名称到工具参数名称的映射。
         * <p>
         * -- GETTER --
         * 获取业务 Header 映射。
         *
         * @return 保持配置顺序的业务 Header 映射
         */
        private Map<String, String> business = new LinkedHashMap<>();

        /**
         * 设置业务 Header 映射，并执行空值归一化和防御性复制。
         *
         * @param business 下游名称到工具参数名称的映射
         *                 返回值：无。
         */
        public void setBusiness(Map<String, String> business) {
            this.business = business == null ? new LinkedHashMap<>() : new LinkedHashMap<>(business);
        }
    }
}
