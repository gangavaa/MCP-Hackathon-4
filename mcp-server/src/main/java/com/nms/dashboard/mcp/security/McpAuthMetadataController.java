package com.nms.dashboard.mcp.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The OAuth Protected Resource Metadata (RFC 9728) an MCP client reads after its first 401 from {@code /mcp}: which
 * authorization server issues tokens for it. Claude Code and the Desktop bridge find Keycloak here.
 */
@RestController
public class McpAuthMetadataController {

  /** RFC 9728 places a resource's metadata under the well-known prefix followed by the resource's path. */
  static final String METADATA_PATH = "/.well-known/oauth-protected-resource/mcp";

  private final String issuer;
  private final String publicUrl;

  public McpAuthMetadataController(@Value("${app.auth.issuer}") String issuer, @Value("${app.public-url}") String publicUrl) {
    this.issuer = issuer;
    this.publicUrl = publicUrl;
  }

  @GetMapping({METADATA_PATH, "/.well-known/oauth-protected-resource"})
  public Map<String, Object> protectedResource() {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("resource", publicUrl + "/mcp");
    metadata.put("resource_name", "NMS Dashboard");
    metadata.put("authorization_servers", List.of(issuer));
    metadata.put("bearer_methods_supported", List.of("header"));
    metadata.put("resource_documentation", publicUrl + "/");
    return metadata;
  }
}
