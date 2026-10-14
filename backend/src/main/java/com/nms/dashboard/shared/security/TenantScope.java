package com.nms.dashboard.shared.security;

import java.util.Set;
import java.util.TreeSet;

/**
 * The tenants a user may see. Passed to every query explicitly — there is no "unscoped" way to run one — and
 * applied by {@link com.nms.dashboard.analytics.infrastructure.QueryBuilder} as {@code t.id IN (...)}.
 */
public record TenantScope(boolean all, Set<String> ids) {

  public static final TenantScope ALL = new TenantScope(true, Set.of());
  public static final TenantScope NONE = new TenantScope(false, Set.of());

  public static TenantScope of(Set<String> ids) {
    return new TenantScope(false, Set.copyOf(ids));
  }

  /** Stable across equal scopes — the catalogue of dimension values is cached per key. */
  public String key() {
    return all ? "*" : String.join(",", new TreeSet<>(ids));
  }
}
