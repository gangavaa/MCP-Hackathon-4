package com.nms.dashboard.report.domain;

import java.util.List;
import java.util.Map;

/**
 * A report's answer: its columns and rows, the parameters it ran with (defaults filled in), the data's date and —
 * for a windowed report — the days the window covers.
 */
public record ReportResult(String report, String title, List<ReportColumn> columns, List<Map<String, Object>> rows,
    Map<String, Object> params, String anchor, Window window) {

  /** The first and the last day inside the window, as YYYY-MM-DD. */
  public record Window(String from, String to) {}
}
