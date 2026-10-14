package com.nms.dashboard.shared.security;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Keycloak sessions signed out through {@code POST /api/auth/logout}. Keycloak stops renewing them at once, but an
 * access token it already issued stays valid until it expires; {@link SecurityConfig} refuses a token whose
 * {@code sid} is listed here, so a sign-out takes effect on the next request. An entry lasts as long as the last
 * token that could carry it — in memory, so a restart forgets it, and such a token then works until it expires.
 */
@Component
public class SignedOutSessions {

  private final Map<String, Instant> sessions = new ConcurrentHashMap<>();

  public void add(String sessionId, Instant tokenExpiresAt) {
    Instant now = Instant.now();
    sessions.values().removeIf(until -> !until.isAfter(now));
    sessions.put(sessionId, tokenExpiresAt);
  }

  public boolean contains(String sessionId) {
    if (sessionId == null) return false;
    Instant until = sessions.get(sessionId);
    return until != null && until.isAfter(Instant.now());
  }
}
