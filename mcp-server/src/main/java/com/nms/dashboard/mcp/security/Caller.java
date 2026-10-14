package com.nms.dashboard.mcp.security;

import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Who called a tool, and the access token to call the API with on their behalf — the very token this request
 * arrived with, already verified. This server decides nothing from it: tenants and access are the API's to apply.
 * {@code sessionId} ({@code sid}) and {@code expiresAt} are what the {@code logout} tool needs to refuse the token after.
 */
public record Caller(String username, String token, String sessionId, Instant expiresAt) {

  /** Sign-in switched off (app.auth.enabled=false, and the API's too): calls go without a token. */
  public static final Caller LOCAL = new Caller("local", null, null, null);

  /** The signed-in caller of the current request, or null when it carries no verified token. */
  public static Caller current() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth instanceof JwtAuthenticationToken jwt) {
      String username = jwt.getToken().getClaimAsString("preferred_username");
      return new Caller(username != null ? username : jwt.getName(), jwt.getToken().getTokenValue(),
          jwt.getToken().getClaimAsString("sid"), jwt.getToken().getExpiresAt());
    }
    return null;
  }
}
