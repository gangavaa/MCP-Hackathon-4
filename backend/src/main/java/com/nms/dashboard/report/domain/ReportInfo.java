package com.nms.dashboard.report.domain;

import java.util.List;

/**
 * One report as {@code GET /api/reports} lists it: where to call it, what it takes and what it answers.
 * {@code values} lists what a parameter may hold, inside the caller's tenants, when it is short enough to list.
 */
public record ReportInfo(String id, String path, String title, String description, String chartType,
    List<Param> params, List<ReportColumn> columns, Drill drill) {

  public record Param(String key, String description, Integer defaultValue, List<String> values) {}

  /** A click on a category value opens {@code report} with that value as {@code param}. */
  public record Drill(String param, String report) {}
}
