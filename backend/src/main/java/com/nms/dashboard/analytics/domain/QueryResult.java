package com.nms.dashboard.analytics.domain;

import java.util.List;
import java.util.Map;

/** {@code sql} has its parameters inlined, for reading only — a "Show SQL" view. */
public record QueryResult(List<QueryColumn> columns, List<Map<String, Object>> rows, String sql, String anchor, Window window) {

  public record Window(String from, String to) {}
}
