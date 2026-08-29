package ai.opencode.mcp.autoconfigure;

import java.time.Duration;

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
}
