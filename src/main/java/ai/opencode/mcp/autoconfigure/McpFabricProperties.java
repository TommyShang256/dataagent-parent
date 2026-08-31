package ai.opencode.mcp.autoconfigure;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("opencode.mcp")
@Getter
@Setter
public class McpFabricProperties {

  private boolean enabled = true;

  private String endpoint = "/rest/mcp";

  private String serverName = "dataagent-mcp";

  private String serverVersion = "0.1.0";

  private Duration requestTimeout = Duration.ofMinutes(5);

  private Duration keepAlive;

  private DataSize maxRequestSize = DataSize.ofMegabytes(16);

  private ApiFabric apiFabric = new ApiFabric();

  private Cse cse = new Cse();

  public void setApiFabric(ApiFabric apiFabric) { this.apiFabric = apiFabric == null ? new ApiFabric() : apiFabric; }

  public void setCse(Cse cse) { this.cse = cse == null ? new Cse() : cse; }

  @Getter
  @Setter
  public static final class ApiFabric {
    private String baseUrl;
    private Map<String, ApiFabricEndpoint> endpoints = new LinkedHashMap<>();

    public void setEndpoints(Map<String, ApiFabricEndpoint> endpoints) {
      this.endpoints = endpoints == null ? new LinkedHashMap<>() : new LinkedHashMap<>(endpoints);
    }
  }

  @Getter
  @Setter
  public static final class Cse {
    private Map<String, CseEndpoint> endpoints = new LinkedHashMap<>();

    public void setEndpoints(Map<String, CseEndpoint> endpoints) {
      this.endpoints = endpoints == null ? new LinkedHashMap<>() : new LinkedHashMap<>(endpoints);
    }
  }

  @Getter
  @Setter
  public abstract static class Endpoint {
    private String method;
    private Map<String, String> query = new LinkedHashMap<>();
    private Headers headers = new Headers();

    public void setQuery(Map<String, String> query) {
      this.query = query == null ? new LinkedHashMap<>() : new LinkedHashMap<>(query);
    }

    public void setHeaders(Headers headers) { this.headers = headers == null ? new Headers() : headers; }
  }

  @Getter
  @Setter
  public static final class ApiFabricEndpoint extends Endpoint {
    private String pathTemplate;
  }

  @Getter
  @Setter
  public static final class CseEndpoint extends Endpoint {
    private String uriTemplate;
  }

  @Getter
  @Setter
  public static final class Headers {
    private Map<String, String> business = new LinkedHashMap<>();

    public void setBusiness(Map<String, String> business) {
      this.business = business == null ? new LinkedHashMap<>() : new LinkedHashMap<>(business);
    }
  }
}
