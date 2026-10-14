package com.nms.dashboard.report.domain;

/** One column of a report's rows. {@code kind} is "dimension" or "metric"; {@code key} is the SQL column alias. */
public record ReportColumn(String key, String label, String kind) {

  public static ReportColumn dimension(String key, String label) {
    return new ReportColumn(key, label, "dimension");
  }

  public static ReportColumn metric(String key, String label) {
    return new ReportColumn(key, label, "metric");
  }
}
