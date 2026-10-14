package com.nms.dashboard.shared.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Ends a user's Keycloak session through the Account API, with their own access token — a user may end their own
 * sessions (role {@code manage-account}, audience {@code account}), so the API needs no admin credentials. Once it is
 * ended, the session's refresh token is dead: no client can renew its sign-in without the user signing in again.
 */
@Component
public class KeycloakSessions {

  private final RestClient http;

  public KeycloakSessions(RestClient.Builder builder, @Value("${app.auth.account-uri}") String accountUri) {
    this.http = builder.baseUrl(accountUri).build();
  }

  public void end(Jwt token) {
    String sessionId = token.getClaimAsString("sid");
    if (sessionId == null) throw new IllegalStateException("The access token names no session (sid) to sign out of.");
    try {
      http.delete().uri("/sessions/{id}", sessionId)
          .headers(h -> h.setBearerAuth(token.getTokenValue()))
          .accept(MediaType.APPLICATION_JSON)
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientResponseException e) {
      // Already ended (signed out elsewhere, or expired): the result the caller wanted.
      if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) return;
      throw new IllegalStateException("Keycloak refused the sign-out (" + e.getStatusCode().value() + "): " + e.getResponseBodyAsString());
    } catch (ResourceAccessException e) {
      throw new IllegalStateException("Keycloak is not reachable to sign out: " + e.getMessage());
    }
  }
}
