package com.nms.dashboard.report.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * Every parameter a report may take — the whole vocabulary of the report API. A report declares which of these it
 * takes; each one is a value bound into that report's fixed statement, never a part of the SQL.
 */
public enum ReportParam {
  TENANT("tenant", "Tenant name, e.g. Dairy Prod."),
  COUNTRY("country", "Country name, e.g. Germany."),
  FARM("farm", "Farm name, e.g. Hoeve De Linde."),
  FIRMWARE("firmware", "Device firmware version, e.g. 5.16.0."),
  ISSUE_TYPE("issueType", "Issue type — the alert's description, e.g. Gateway not answering."),
  SEVERITY("severity", "CRITICAL, HIGH, MEDIUM or LOW."),
  DAYS("days", "The window: this many days (1–60), ending at the data's date."),
  OFFSET_DAYS("offsetDays", "Shifts the window back this many days (0–60) — 7 with days 7 is the week before."),
  LIMIT("limit", "At most this many rows (1–50).");

  public static final int MAX_DAYS = 60;
  public static final int MAX_LIMIT = 50;

  private final String key;
  private final String description;

  ReportParam(String key, String description) {
    this.key = key;
    this.description = description;
  }

  /** The name it travels under: the query-string parameter of {@code GET /api/reports/<id>}. */
  public String key() {
    return key;
  }

  public String description() {
    return description;
  }

  public static Optional<ReportParam> byKey(String key) {
    return Arrays.stream(values()).filter(p -> p.key.equals(key)).findFirst();
  }
}
