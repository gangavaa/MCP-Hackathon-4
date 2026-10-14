package com.nms.dashboard.shared.security;

import com.nms.dashboard.shared.error.ValidationException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Signing in and out: the settings the Angular app signs in with, and the end of a sign-in. */
@RestController
public class AuthController {

  private static final Logger audit = LoggerFactory.getLogger("audit");

  private final boolean enabled;
  private final String issuer;
  private final String clientId;
  private final KeycloakSessions keycloak;
  private final SignedOutSessions signedOut;

  public AuthController(@Value("${app.auth.enabled}") boolean enabled, @Value("${app.auth.issuer}") String issuer,
      @Value("${app.auth.client-id}") String clientId, KeycloakSessions keycloak, SignedOutSessions signedOut) {
    this.enabled = enabled;
    this.issuer = issuer;
    this.clientId = clientId;
    this.keycloak = keycloak;
    this.signedOut = signedOut;
  }

  /** What the Angular app needs to sign in — read at start-up, so one build works against any issuer. */
  @GetMapping("/api/auth/config")
  public Map<String, Object> authConfig() {
    return enabled ? Map.of("enabled", true, "issuer", issuer, "clientId", clientId) : Map.of("enabled", false);
  }

  /**
   * Signs the caller out of the session their token belongs to: Keycloak ends it, so it can no longer be renewed, and
   * this API refuses its tokens from the next request on. Other sessions of the same user — another browser, another
   * app — stay signed in.
   */
  @PostMapping("/api/auth/logout")
  public Map<String, Object> logout(@AuthenticationPrincipal Jwt token) {
    if (!enabled || token == null) throw new ValidationException("sign-out", List.of("sign-in is switched off, so there is nothing to sign out of"));
    keycloak.end(token);
    signedOut.add(token.getClaimAsString("sid"), token.getExpiresAt());
    String username = token.getClaimAsString("preferred_username");
    audit.info("user={} signed out (session {})", username, token.getClaimAsString("sid"));
    return Map.of("signedOut", true, "username", username);
  }
}
