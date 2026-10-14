package com.nms.dashboard.mcp.security;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Sessions signed out through the {@code logout} tool. The API ends them in Keycloak and refuses their tokens; this
 * server refuses them too ({@link McpSecurityConfig}), so the next {@code /mcp} request gets the 401 that makes the
 * MCP client sign in again — rather than tool calls that reach the API only to be turned away. In memory: an entry
 * lasts as long as the last token that could carry it.
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
