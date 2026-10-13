package com.nms.askdashboard.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Discovery. The OAuth Protected Resource Metadata (RFC 9728) an MCP client reads after its first 401 — which
 * authorization server issues tokens for {@code /mcp} — and the settings the Angular app signs in with.
 */
@RestController
public class ProtectedResourceController {

  /** RFC 9728 places a resource's metadata under the well-known prefix followed by the resource's path. */
  static final String MCP_METADATA_PATH = "/.well-known/oauth-protected-resource/mcp";

  private final boolean enabled;
  private final String issuer;
  private final String clientId;
  private final String publicUrl;

  public ProtectedResourceController(@Value("${app.auth.enabled}") boolean enabled, @Value("${app.auth.issuer}") String issuer,
      @Value("${app.auth.client-id}") String clientId, @Value("${app.public-url}") String publicUrl) {
    this.enabled = enabled;
    this.issuer = issuer;
    this.clientId = clientId;
    this.publicUrl = publicUrl;
  }

  @GetMapping({MCP_METADATA_PATH, "/.well-known/oauth-protected-resource"})
  public Map<String, Object> protectedResource() {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("resource", publicUrl + "/mcp");
    metadata.put("resource_name", "NMS Ask the Dashboard");
    metadata.put("authorization_servers", List.of(issuer));
    metadata.put("bearer_methods_supported", List.of("header"));
    metadata.put("resource_documentation", publicUrl + "/");
    return metadata;
  }

  /** What the Angular app needs to sign in — read at start-up, so one build works against any issuer. */
  @GetMapping("/api/auth/config")
  public Map<String, Object> authConfig() {
    return enabled ? Map.of("enabled", true, "issuer", issuer, "clientId", clientId) : Map.of("enabled", false);
  }
}
