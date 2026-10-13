package com.nms.askdashboard.security;

import java.util.List;
import java.util.Set;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who is asking, and what they may see and do — read from the access token, never from the request body. Every
 * query runs inside {@link #tenants()}; every change to a dashboard needs {@link #canWrite()}.
 *
 * <p>Claims (Keycloak client scope {@code nms}): {@code tenants} — tenant ids, or {@code *} for every tenant;
 * {@code nms_access} — {@code read} or {@code write}.
 */
public record NmsUser(String subject, String username, String name, TenantScope tenants, boolean canWrite) {

  /** Sign-in switched off (app.auth.enabled=false): one local user who sees and changes everything. */
  public static final NmsUser LOCAL = new NmsUser("local", "local", "Local user", TenantScope.ALL, true);

  public static NmsUser fromJwt(Jwt jwt) {
    List<String> tenants = jwt.getClaimAsStringList("tenants");
    TenantScope scope = tenants == null ? TenantScope.NONE : tenants.contains("*") ? TenantScope.ALL : TenantScope.of(Set.copyOf(tenants));
    String username = jwt.getClaimAsString("preferred_username");
    String name = jwt.getClaimAsString("name");
    return new NmsUser(jwt.getSubject(), username, name != null ? name : username, scope, "write".equals(jwt.getClaimAsString("nms_access")));
  }

  /** Each user has their own dashboard. */
  public String owner() {
    return subject;
  }

  /**
   * An admin sees every tenant ({@code tenants: *}) and has write access — the only user who may create, edit or
   * delete tenants. Swap for a dedicated role claim when the realm grows one.
   */
  public boolean isAdmin() {
    return tenants.all() && canWrite;
  }

  public void requireAdmin() {
    if (!isAdmin()) throw new ForbiddenException("Only an admin can create, edit or delete tenants — " + username + " is not an admin.");
  }

  public void requireWrite() {
    if (!canWrite) throw new ForbiddenException("Your account (" + username + ") has read-only access — it can't change the dashboard.");
  }
}
