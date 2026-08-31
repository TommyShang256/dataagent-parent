package ai.opencode.mcp.autoconfigure;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("opencode.mcp")
public class McpFabricProperties {

  private boolean enabled = true;

  private String endpoint = "/mcp";

  private String serverName = "dataagent-mcp";

  private String serverVersion = "0.1.0";

  private Duration requestTimeout = Duration.ofMinutes(5);

  private Duration keepAlive;

  private DataSize maxRequestSize = DataSize.ofMegabytes(16);

  private ApiFabric apiFabric = new ApiFabric();

  private Cse cse = new Cse();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getEndpoint() {
    return endpoint;
  }

  public void setEndpoint(String endpoint) {
    this.endpoint = endpoint;
  }

  public String getServerName() {
    return serverName;
  }

  public void setServerName(String serverName) {
    this.serverName = serverName;
  }

  public String getServerVersion() {
    return serverVersion;
  }

  public void setServerVersion(String serverVersion) {
    this.serverVersion = serverVersion;
  }

  public Duration getRequestTimeout() {
    return requestTimeout;
  }

  public void setRequestTimeout(Duration requestTimeout) {
    this.requestTimeout = requestTimeout;
  }

  public Duration getKeepAlive() {
    return keepAlive;
  }

  public void setKeepAlive(Duration keepAlive) {
    this.keepAlive = keepAlive;
  }

  public DataSize getMaxRequestSize() {
    return maxRequestSize;
  }

  public void setMaxRequestSize(DataSize maxRequestSize) {
    this.maxRequestSize = maxRequestSize;
  }

  public ApiFabric getApiFabric() { return apiFabric; }

  public void setApiFabric(ApiFabric apiFabric) { this.apiFabric = apiFabric == null ? new ApiFabric() : apiFabric; }

  public Cse getCse() { return cse; }

  public void setCse(Cse cse) { this.cse = cse == null ? new Cse() : cse; }

  public static final class ApiFabric {
    private String baseUrl;
    private Map<String, ApiFabricEndpoint> endpoints = new LinkedHashMap<>();
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public Map<String, ApiFabricEndpoint> getEndpoints() { return endpoints; }
    public void setEndpoints(Map<String, ApiFabricEndpoint> endpoints) {
      this.endpoints = endpoints == null ? new LinkedHashMap<>() : new LinkedHashMap<>(endpoints);
    }
  }

  public static final class Cse {
    private Map<String, CseEndpoint> endpoints = new LinkedHashMap<>();
    public Map<String, CseEndpoint> getEndpoints() { return endpoints; }
    public void setEndpoints(Map<String, CseEndpoint> endpoints) {
      this.endpoints = endpoints == null ? new LinkedHashMap<>() : new LinkedHashMap<>(endpoints);
    }
  }

  public abstract static class Endpoint {
    private String method;
    private Map<String, String> query = new LinkedHashMap<>();
    private Headers headers = new Headers();
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public Map<String, String> getQuery() { return query; }
    public void setQuery(Map<String, String> query) {
      this.query = query == null ? new LinkedHashMap<>() : new LinkedHashMap<>(query);
    }
    public Headers getHeaders() { return headers; }
    public void setHeaders(Headers headers) { this.headers = headers == null ? new Headers() : headers; }
  }

  public static final class ApiFabricEndpoint extends Endpoint {
    private String pathTemplate;
    public String getPathTemplate() { return pathTemplate; }
    public void setPathTemplate(String pathTemplate) { this.pathTemplate = pathTemplate; }
  }

  public static final class CseEndpoint extends Endpoint {
    private String uriTemplate;
    public String getUriTemplate() { return uriTemplate; }
    public void setUriTemplate(String uriTemplate) { this.uriTemplate = uriTemplate; }
  }

  public static final class Headers {
    private Map<String, String> business = new LinkedHashMap<>();
    public Map<String, String> getBusiness() { return business; }
    public void setBusiness(Map<String, String> business) {
      this.business = business == null ? new LinkedHashMap<>() : new LinkedHashMap<>(business);
    }
  }
}
