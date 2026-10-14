package com.nms.dashboard.report.domain;

import static com.nms.dashboard.report.domain.ReportColumn.dimension;
import static com.nms.dashboard.report.domain.ReportColumn.metric;
import static com.nms.dashboard.report.domain.ReportParam.COUNTRY;
import static com.nms.dashboard.report.domain.ReportParam.DAYS;
import static com.nms.dashboard.report.domain.ReportParam.FARM;
import static com.nms.dashboard.report.domain.ReportParam.FIRMWARE;
import static com.nms.dashboard.report.domain.ReportParam.ISSUE_TYPE;
import static com.nms.dashboard.report.domain.ReportParam.LIMIT;
import static com.nms.dashboard.report.domain.ReportParam.OFFSET_DAYS;
import static com.nms.dashboard.report.domain.ReportParam.SEVERITY;
import static com.nms.dashboard.report.domain.ReportParam.TENANT;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The fixed reports: every question the API answers about the estate. Each is one endpoint
 * ({@code GET /api/reports/<id>}) running one fixed statement, with the parameters it declares — the way
 * nms-issues-service answers fixed gRPC methods with the fixed statements of {@code IssueQ}. Nothing a caller sends
 * changes which columns, groupings or tables a report reads.
 *
 * <p>{@code defaultDays}: the window when {@code days} is left out — {@code null} means all history.
 * {@code defaultLimit}: the row cap when {@code limit} is left out. {@code drill}: the report a click on a category
 * opens, with the clicked value as its parameter.
 */
public enum Report {

  FARMS_BY_TENANT("farms-by-tenant", "Farms by tenant", "bar",
      "How many farms each tenant has.",
      List.of(COUNTRY),
      List.of(dimension("tenant", "Tenant"), metric("farm_count", "Farms")),
      null, null, new Drill(TENANT, "farms-by-country")),

  FARMS_BY_COUNTRY("farms-by-country", "Farms by country", "bar",
      "How many farms are in each country.",
      List.of(TENANT),
      List.of(dimension("country", "Country"), metric("farm_count", "Farms")),
      null, null, null),

  OFFLINE_DEVICES_BY_FARM("offline-devices-by-farm", "Offline devices by farm", "hbar",
      "The farms with the most devices offline right now (a snapshot of now, not a history).",
      List.of(TENANT, COUNTRY, FIRMWARE, LIMIT),
      List.of(dimension("farm", "Farm"), metric("offline_count", "Offline devices")),
      null, 10, new Drill(FARM, "offline-devices-by-firmware")),

  OFFLINE_DEVICES_BY_FIRMWARE("offline-devices-by-firmware", "Offline % by firmware", "bar",
      "The share of devices offline right now, per firmware version — to tell whether a firmware is the problem.",
      List.of(TENANT, COUNTRY, FARM),
      List.of(dimension("firmware", "Firmware"), metric("offline_pct", "Offline %")),
      null, null, new Drill(FIRMWARE, "offline-devices-by-farm")),

  LOW_BATTERY_DEVICES_BY_COUNTRY("low-battery-devices-by-country", "Low-battery devices by country", "bar",
      "How many devices are on low battery right now, per country.",
      List.of(TENANT),
      List.of(dimension("country", "Country"), metric("low_battery_count", "Low-battery devices")),
      null, null, null),

  CONNECTIVITY_TREND("connectivity-trend", "Offline % per day", "area",
      "The share of devices offline, day by day — the estate's connectivity over time.",
      List.of(TENANT, COUNTRY, FARM, DAYS, OFFSET_DAYS),
      List.of(dimension("period", "Day"), metric("offline_pct", "Offline %")),
      30, null, null),

  ISSUES_BY_COUNTRY("issues-by-country", "Issues by country", "bar",
      "Issues raised per country — within the window when days is given, otherwise over all 60 days of history.",
      List.of(TENANT, ISSUE_TYPE, SEVERITY, DAYS, OFFSET_DAYS, LIMIT),
      List.of(dimension("country", "Country"), metric("issue_count", "Issues")),
      null, 25, new Drill(COUNTRY, "issues-by-farm")),

  ISSUES_BY_FARM("issues-by-farm", "Issues by farm", "hbar",
      "The farms that raised the most issues — within the window when days is given, otherwise over all history.",
      List.of(TENANT, COUNTRY, ISSUE_TYPE, SEVERITY, DAYS, OFFSET_DAYS, LIMIT),
      List.of(dimension("farm", "Farm"), metric("issue_count", "Issues")),
      null, 10, new Drill(FARM, "issues-by-type")),

  ISSUES_BY_TYPE("issues-by-type", "Issues by type", "hbar",
      "The issue types raised most — within the window when days is given, otherwise over all history.",
      List.of(TENANT, COUNTRY, FARM, SEVERITY, DAYS, OFFSET_DAYS, LIMIT),
      List.of(dimension("issue_type", "Issue type"), metric("issue_count", "Issues")),
      null, 10, new Drill(ISSUE_TYPE, "issues-by-farm")),

  ISSUE_TREND_BY_SEVERITY("issue-trend-by-severity", "Issues raised per day by severity", "line",
      "Issues raised each day, one series per severity.",
      List.of(TENANT, COUNTRY, DAYS, OFFSET_DAYS),
      List.of(dimension("period", "Day"), dimension("severity", "Severity"), metric("issue_count", "Issues")),
      14, null, null),

  CRITICAL_ISSUES("critical-issues", "Critical issues", "kpi",
      "How many critical issues were raised in the window — one number.",
      List.of(TENANT, COUNTRY, DAYS, OFFSET_DAYS),
      List.of(metric("critical_count", "Critical issues")),
      7, null, null),

  OPEN_ISSUES_BY_SEVERITY("open-issues-by-severity", "Open issues by severity", "donut",
      "Issues not yet resolved, per severity.",
      List.of(TENANT, COUNTRY, FARM),
      List.of(dimension("severity", "Severity"), metric("open_count", "Open issues")),
      null, null, new Drill(SEVERITY, "open-issues-by-type")),

  OPEN_ISSUES_BY_TYPE("open-issues-by-type", "Open issues by type", "hbar",
      "The issue types with the most issues not yet resolved.",
      List.of(TENANT, COUNTRY, FARM, SEVERITY, LIMIT),
      List.of(dimension("issue_type", "Issue type"), metric("open_count", "Open issues")),
      null, 10, null),

  OPEN_ISSUES_BY_TENANT("open-issues-by-tenant", "Open issues by tenant", "bar",
      "Issues not yet resolved, per tenant and severity.",
      List.of(COUNTRY, SEVERITY),
      List.of(dimension("tenant", "Tenant"), dimension("severity", "Severity"), metric("open_count", "Open issues")),
      null, null, new Drill(TENANT, "open-issues-by-type"));

  /** A click on a category value opens {@code report} with that value as {@code param}. */
  public record Drill(ReportParam param, String report) {}

  private final String id;
  private final String title;
  private final String chartType;
  private final String description;
  private final List<ReportParam> params;
  private final List<ReportColumn> columns;
  private final Integer defaultDays;
  private final Integer defaultLimit;
  private final Drill drill;

  Report(String id, String title, String chartType, String description, List<ReportParam> params,
      List<ReportColumn> columns, Integer defaultDays, Integer defaultLimit, Drill drill) {
    this.id = id;
    this.title = title;
    this.chartType = chartType;
    this.description = description;
    this.params = params;
    this.columns = columns;
    this.defaultDays = defaultDays;
    this.defaultLimit = defaultLimit;
    this.drill = drill;
  }

  public String id() {
    return id;
  }

  public String title() {
    return title;
  }

  /** How the dashboard and the chat draw it unless told otherwise. */
  public String chartType() {
    return chartType;
  }

  public String description() {
    return description;
  }

  public List<ReportParam> params() {
    return params;
  }

  public boolean takes(ReportParam param) {
    return params.contains(param);
  }

  public List<ReportColumn> columns() {
    return columns;
  }

  public Integer defaultDays() {
    return defaultDays;
  }

  public Integer defaultLimit() {
    return defaultLimit;
  }

  public Drill drill() {
    return drill;
  }

  public static Optional<Report> byId(String id) {
    return Arrays.stream(values()).filter(r -> r.id.equals(id)).findFirst();
  }
}
