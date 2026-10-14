package com.nms.dashboard.analytics.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nms.dashboard.analytics.domain.QueryException;
import com.nms.dashboard.analytics.domain.QueryFilter;
import com.nms.dashboard.analytics.domain.QueryRequest;
import com.nms.dashboard.analytics.domain.TimeRange;
import com.nms.dashboard.shared.security.TenantScope;
import java.util.Set;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryBuilderTest {

  static final Instant ANCHOR = Instant.parse("2026-10-12T00:00:00Z");

  static QueryRequest q(String dataset, String metric, List<String> groupBy, List<QueryFilter> filters) {
    return new QueryRequest(dataset, metric, groupBy, filters, null, null, null, null);
  }

  @Test
  void passesEveryCallerValueAsAParameterNeverInTheSql() {
    String evil = "'; DROP TABLE farms; --";
    QueryBuilder.Built built = QueryBuilder.build(q("devices", "offline_count", List.of("farm"),
        List.of(new QueryFilter("country", "eq", evil), new QueryFilter("farm", "contains", evil))), ANCHOR, TenantScope.ALL);
    assertThat(built.sql()).doesNotContain("DROP");
    assertThat(built.params()).containsExactly(evil, "%" + evil + "%");
  }

  @Test
  void refusesIdentifiersOutsideTheCatalogueListingTheOnesItHas() {
    assertThatThrownBy(() -> QueryBuilder.build(q("devices", "offline_count", List.of("farm; DROP TABLE x"), null), ANCHOR, TenantScope.ALL))
        .isInstanceOf(QueryException.class);
    assertThatThrownBy(() -> QueryBuilder.build(q("devices", "nope", null, null), ANCHOR, TenantScope.ALL))
        .hasMessageContaining("Available: device_count, offline_count");
    assertThatThrownBy(() -> QueryBuilder.build(q("users", "x", null, null), ANCHOR, TenantScope.ALL))
        .hasMessageContaining("Available: farms, devices, issues, connectivity");
    assertThatThrownBy(() -> QueryBuilder.build(q("devices", "device_count", null, List.of(new QueryFilter("secret", "eq", 1))), ANCHOR, TenantScope.ALL))
        .hasMessageContaining("battery_pct");
  }

  @Test
  void expandsAnInFilterToOneParameterPerValue() {
    QueryBuilder.Built built = QueryBuilder.build(q("farms", "farm_count", null, List.of(new QueryFilter("country", "in", List.of("Germany", "France")))), ANCHOR, TenantScope.ALL);
    assertThat(built.sql()).contains("f.country IN (?, ?)");
    assertThat(built.params()).containsExactly("Germany", "France");
  }

  @Test
  void placesATimeWindowRelativeToTheAnchorShiftedByOffsetDays() {
    QueryRequest req = new QueryRequest("issues", "issue_count", null, null, new TimeRange(7, 7), null, null, null);
    QueryBuilder.Built built = QueryBuilder.build(req, ANCHOR, TenantScope.ALL);
    assertThat(built.params()).containsExactly(
        OffsetDateTime.of(2026, 9, 28, 0, 0, 0, 0, ZoneOffset.UTC), OffsetDateTime.of(2026, 10, 5, 0, 0, 0, 0, ZoneOffset.UTC));
    assertThat(built.window().from()).isEqualTo("2026-09-28");
    assertThat(built.window().to()).isEqualTo("2026-10-04");
  }

  @Test
  void ordersSeveritiesCriticalToLowAndKeepsLaterDimensionsInTheirColumn() {
    QueryRequest req = new QueryRequest("issues", "open_count", List.of("severity", "tenant"), null, null, null, "dimension", null);
    assertThat(QueryBuilder.build(req, ANCHOR, TenantScope.ALL).sql()).contains("ORDER BY CASE i.severity WHEN 'CRITICAL' THEN 1").endsWith(", 2\nLIMIT 25");
  }

  @Test
  void capsGroupByAtTwoDimensionsAndTheLimitAt500() {
    assertThatThrownBy(() -> QueryBuilder.build(q("issues", "issue_count", List.of("tenant", "severity", "farm"), null), ANCHOR, TenantScope.ALL))
        .hasMessageContaining("at most two");
    QueryRequest big = new QueryRequest("issues", "issue_count", List.of("farm"), null, null, null, null, 10_000);
    assertThat(QueryBuilder.build(big, ANCHOR, TenantScope.ALL).sql()).endsWith("LIMIT 500");
  }

  @Test
  void inlinesParametersForDisplayOnly() {
    assertThat(QueryBuilder.inline("a = ? AND b = ?", List.of("O'Brien", 3))).isEqualTo("a = 'O''Brien' AND b = 3");
  }

  static final String DAIRY_PROD = "77c2c8fc-1266-46e5-937f-e1c84fe42fb8";
  static final String DAIRY_DEV = "e2362248-5c59-44e3-b5f3-d551a879835a";

  @Test
  void confinesEveryQueryToTheCallersTenantsBeforeAnyOtherFilter() {
    QueryBuilder.Built built = QueryBuilder.build(q("devices", "offline_count", List.of("farm"),
        List.of(new QueryFilter("country", "eq", "Germany"))), ANCHOR, TenantScope.of(Set.of(DAIRY_PROD, DAIRY_DEV)));
    assertThat(built.sql()).contains("WHERE t.id IN (?, ?) AND f.country = ?");
    assertThat(built.params()).containsExactly(DAIRY_PROD, DAIRY_DEV, "Germany");
  }

  @Test
  void aUserWithNoTenantsSeesNothing() {
    assertThat(QueryBuilder.build(q("farms", "farm_count", null, null), ANCHOR, TenantScope.NONE).sql()).contains("WHERE FALSE");
  }

  @Test
  void aFilterOnTenantCannotWidenTheScope() {
    // The model may filter by tenant name, but the scope clause is always there too: the two are ANDed.
    QueryBuilder.Built built = QueryBuilder.build(q("farms", "farm_count", null, List.of(new QueryFilter("tenant", "eq", "Feedlot Prod"))),
        ANCHOR, TenantScope.of(Set.of(DAIRY_PROD)));
    assertThat(built.sql()).contains("t.id IN (?) AND t.name = ?");
  }

  @Test
  void refusesToBuildWithoutAScope() {
    assertThatThrownBy(() -> QueryBuilder.build(q("farms", "farm_count", null, null), ANCHOR, null)).isInstanceOf(IllegalArgumentException.class);
  }
}
