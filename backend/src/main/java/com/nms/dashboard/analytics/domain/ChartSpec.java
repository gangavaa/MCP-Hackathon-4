package com.nms.dashboard.analytics.domain;

/**
 * A chart: the query, how to draw it (bar | hbar | line | area | pie | donut | table | kpi), whether to add the
 * previous period as a second series, and a one-sentence insight about it. Pinned widgets store this — the
 * question, not the data — so they stay live.
 */
public record ChartSpec(String title, String chartType, QueryRequest query, String compare, String insight) {

  public boolean comparesPreviousPeriod() {
    return "previous_period".equals(compare) && query != null && query.timeRange() != null;
  }
}
