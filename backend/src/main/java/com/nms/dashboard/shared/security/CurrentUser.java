package com.nms.dashboard.shared.security;

import com.nms.dashboard.shared.error.ForbiddenException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** The signed-in user of the current request — or {@link NmsUser#LOCAL} when sign-in is switched off. */
@Component
public class CurrentUser {

  private final boolean enabled;

  public CurrentUser(@Value("${app.auth.enabled}") boolean enabled) {
    this.enabled = enabled;
  }

  public NmsUser get() {
    if (!enabled) return NmsUser.LOCAL;
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof Jwt jwt) return NmsUser.fromJwt(jwt);
    // Unreachable through the filter chain, which refuses unauthenticated calls — but never default to access.
    throw new ForbiddenException("Not signed in.");
  }
}
