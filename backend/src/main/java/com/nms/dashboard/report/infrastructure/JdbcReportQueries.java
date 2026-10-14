package com.nms.dashboard.report.infrastructure;

import com.nms.dashboard.report.application.ReportQueries;
import com.nms.dashboard.report.domain.Report;
import com.nms.dashboard.report.domain.ReportCriteria;
import com.nms.dashboard.report.domain.ReportParam;
import com.nms.dashboard.shared.persistence.TenantFilter;
import com.nms.dashboard.shared.security.TenantScope;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@link ReportQueries} over Postgres. Each report is one fixed statement below; a caller's parameters only ever bind
 * values into its WHERE and LIMIT. The metrics are the ones the semantic layer defines, so a report and a chart of
 * the same question agree. Every statement starts from {@link TenantFilter}. Names match whatever their case, as the
 * unique indexes on tenant and farm names do.
 */
@Repository
public class JdbcReportQueries implements ReportQueries {

  private static final String FARMS = "farms f JOIN tenants t ON t.id = f.tenant_id";
  private static final String DEVICES = "devices d JOIN farms f ON f.id = d.farm_id JOIN tenants t ON t.id = f.tenant_id";
  private static final String ISSUES = "issues i JOIN alert_definitions a ON a.id = i.definition_id "
      + "JOIN farms f ON f.id = i.farm_id JOIN tenants t ON t.id = i.tenant_id";
  private static final String CONNECTIVITY =
      "connectivity_snapshots c JOIN farms f ON f.id = c.farm_id JOIN tenants t ON t.id = f.tenant_id";

  private static final String OFFLINE_COUNT = "(count(*) FILTER (WHERE d.status = 'OFFLINE'))::int";
  private static final String OFFLINE_PCT =
      "round(100.0 * count(*) FILTER (WHERE d.status = 'OFFLINE') / nullif(count(*), 0), 1)::float8";
  private static final String SNAPSHOT_OFFLINE_PCT = "round(100.0 * sum(c.offline) / nullif(sum(c.total), 0), 1)::float8";
  private static final String SEVERITY_ORDER =
      "CASE i.severity WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 WHEN 'LOW' THEN 4 ELSE 5 END";
  private static final String UTC_DAY = "to_char(%s AT TIME ZONE 'UTC', 'YYYY-MM-DD')";

  /**
   * One report's statement. {@code groups}: how many leading select columns it groups by. {@code time}: the column
   * a window bounds, null for a report of now. {@code condition} and {@code having}: fixed, null for none.
   */
  private record Statement(String from, String select, int groups, String condition, String having, String orderBy, String time) {}

  private final JdbcTemplate jdbc;

  public JdbcReportQueries(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<Map<String, Object>> rows(Report report, ReportCriteria criteria, Instant from, Instant to, TenantScope scope) {
    Statement s = statement(report);
    List<Object> params = new ArrayList<>();
    List<String> where = new ArrayList<>();
    where.add(TenantFilter.condition(scope, params));
    if (s.condition() != null) where.add(s.condition());
    match(where, params, report, ReportParam.TENANT, "t.name", criteria.tenant());
    match(where, params, report, ReportParam.COUNTRY, "f.country", criteria.country());
    match(where, params, report, ReportParam.FARM, "f.name", criteria.farm());
    match(where, params, report, ReportParam.FIRMWARE, "d.firmware_version", criteria.firmware());
    match(where, params, report, ReportParam.ISSUE_TYPE, "a.description", criteria.issueType());
    if (report.takes(ReportParam.SEVERITY) && criteria.severity() != null) {
      where.add("i.severity = ?");
      params.add(criteria.severity());
    }
    if (s.time() != null && from != null && to != null) {
      where.add(s.time() + " >= ? AND " + s.time() + " < ?");
      params.add(OffsetDateTime.ofInstant(from, ZoneOffset.UTC));
      params.add(OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
    }

    StringBuilder sql = new StringBuilder("SELECT ").append(s.select())
        .append("\nFROM ").append(s.from())
        .append("\nWHERE ").append(String.join(" AND ", where));
    if (s.groups() > 0) {
      List<String> ordinals = new ArrayList<>();
      for (int i = 1; i <= s.groups(); i++) ordinals.add(String.valueOf(i));
      sql.append("\nGROUP BY ").append(String.join(", ", ordinals));
    }
    if (s.having() != null) sql.append("\nHAVING ").append(s.having());
    if (s.orderBy() != null) sql.append("\nORDER BY ").append(s.orderBy());
    if (report.takes(ReportParam.LIMIT) && criteria.limit() != null) {
      sql.append("\nLIMIT ?");
      params.add(criteria.limit());
    }
    return jdbc.queryForList(sql.toString(), params.toArray());
  }

  @Override
  public List<String> values(ReportParam param, TenantScope scope) {
    List<Object> params = new ArrayList<>();
    String scoped = TenantFilter.condition(scope, params);
    String sql = switch (param) {
      case TENANT -> "SELECT DISTINCT t.name FROM tenants t WHERE " + scoped + " ORDER BY 1";
      case COUNTRY -> "SELECT DISTINCT f.country FROM " + FARMS + " WHERE " + scoped + " ORDER BY 1";
      case FIRMWARE -> "SELECT DISTINCT d.firmware_version FROM " + DEVICES + " WHERE " + scoped + " ORDER BY 1";
      case ISSUE_TYPE -> "SELECT DISTINCT a.description FROM " + ISSUES + " WHERE " + scoped + " ORDER BY 1";
      case SEVERITY -> "SELECT i.severity FROM " + ISSUES + " WHERE " + scoped + " GROUP BY i.severity ORDER BY " + SEVERITY_ORDER;
      case FARM, DAYS, OFFSET_DAYS, LIMIT -> null;
    };
    return sql == null ? List.of() : jdbc.queryForList(sql, String.class, params.toArray());
  }

  private static Statement statement(Report report) {
    return switch (report) {
      case FARMS_BY_TENANT -> new Statement(FARMS,
          "t.name AS tenant, count(*)::int AS farm_count", 1, null, null, "farm_count DESC, tenant", null);
      case FARMS_BY_COUNTRY -> new Statement(FARMS,
          "f.country AS country, count(*)::int AS farm_count", 1, null, null, "farm_count DESC, country", null);
      case OFFLINE_DEVICES_BY_FARM -> new Statement(DEVICES,
          "f.name AS farm, " + OFFLINE_COUNT + " AS offline_count", 1, null, OFFLINE_COUNT + " > 0",
          "offline_count DESC, farm", null);
      case OFFLINE_DEVICES_BY_FIRMWARE -> new Statement(DEVICES,
          "d.firmware_version AS firmware, " + OFFLINE_PCT + " AS offline_pct", 1, null, null, "firmware", null);
      case LOW_BATTERY_DEVICES_BY_COUNTRY -> new Statement(DEVICES,
          "f.country AS country, count(*)::int AS low_battery_count", 1, "d.status = 'LOW_BATTERY'", null,
          "low_battery_count DESC, country", null);
      case CONNECTIVITY_TREND -> new Statement(CONNECTIVITY,
          "to_char(c.day, 'YYYY-MM-DD') AS period, " + SNAPSHOT_OFFLINE_PCT + " AS offline_pct", 1, null, null,
          "period", "c.day");
      case ISSUES_BY_COUNTRY -> new Statement(ISSUES,
          "f.country AS country, count(*)::int AS issue_count", 1, null, null, "issue_count DESC, country", "i.created_at");
      case ISSUES_BY_FARM -> new Statement(ISSUES,
          "f.name AS farm, count(*)::int AS issue_count", 1, null, null, "issue_count DESC, farm", "i.created_at");
      case ISSUES_BY_TYPE -> new Statement(ISSUES,
          "a.description AS issue_type, count(*)::int AS issue_count", 1, null, null, "issue_count DESC, issue_type",
          "i.created_at");
      case ISSUE_TREND_BY_SEVERITY -> new Statement(ISSUES,
          UTC_DAY.formatted("i.created_at") + " AS period, i.severity AS severity, count(*)::int AS issue_count", 2,
          null, null, "period, " + SEVERITY_ORDER, "i.created_at");
      case CRITICAL_ISSUES -> new Statement(ISSUES,
          "count(*)::int AS critical_count", 0, "i.severity = 'CRITICAL'", null, null, "i.created_at");
      case OPEN_ISSUES_BY_SEVERITY -> new Statement(ISSUES,
          "i.severity AS severity, count(*)::int AS open_count", 1, "i.resolved_at IS NULL", null, SEVERITY_ORDER, null);
      case OPEN_ISSUES_BY_TYPE -> new Statement(ISSUES,
          "a.description AS issue_type, count(*)::int AS open_count", 1, "i.resolved_at IS NULL", null,
          "open_count DESC, issue_type", null);
      case OPEN_ISSUES_BY_TENANT -> new Statement(ISSUES,
          "t.name AS tenant, i.severity AS severity, count(*)::int AS open_count", 2, "i.resolved_at IS NULL", null,
          "tenant, " + SEVERITY_ORDER, null);
    };
  }

  private static void match(List<String> where, List<Object> params, Report report, ReportParam param, String column, String value) {
    if (!report.takes(param) || value == null) return;
    where.add("lower(" + column + ") = lower(?)");
    params.add(value);
  }
}
