package com.nms.dashboard.shared.persistence;

import com.nms.dashboard.shared.security.TenantScope;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The one SQL clause that confines a statement to the caller's tenants — used by the analytics query builder and
 * the estate read model alike. Every statement reaches its tenant as {@code t}. Ids travel as parameters.
 */
public final class TenantFilter {

  private TenantFilter() {}

  /** {@code t.id IN (?, …)}; empty for every tenant; {@code FALSE} for none. */
  public static String clause(TenantScope scope, List<Object> params) {
    if (scope == null) throw new IllegalArgumentException("A query needs a tenant scope.");
    if (scope.all()) return "";
    if (scope.ids().isEmpty()) return "FALSE";
    return "t.id IN (" + scope.ids().stream().sorted().map(id -> {
      params.add(id);
      return "?";
    }).collect(Collectors.joining(", ")) + ")";
  }

  /** As {@link #clause}, but {@code TRUE} for every tenant — for a WHERE that needs a condition. */
  public static String condition(TenantScope scope, List<Object> params) {
    String clause = clause(scope, params);
    return clause.isEmpty() ? "TRUE" : clause;
  }
}
