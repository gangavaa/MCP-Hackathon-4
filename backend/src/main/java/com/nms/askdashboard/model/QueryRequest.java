package com.nms.askdashboard.model;

import java.util.List;

/**
 * The question the model asks — never SQL. {@code groupBy} holds at most two dimensions: the category axis, then
 * the series. {@code timeBucket} (day | week) adds a leading {@code period} dimension.
 */
public record QueryRequest(
    String dataset,
    String metric,
    List<String> groupBy,
    List<QueryFilter> filters,
    TimeRange timeRange,
    String timeBucket,
    String order,
    Integer limit) {

  public List<String> groupByOrEmpty() {
    return groupBy == null ? List.of() : groupBy;
  }

  public List<QueryFilter> filtersOrEmpty() {
    return filters == null ? List.of() : filters;
  }

  public QueryRequest withTimeRange(TimeRange range) {
    return new QueryRequest(dataset, metric, groupBy, filters, range, timeBucket, order, limit);
  }
}
