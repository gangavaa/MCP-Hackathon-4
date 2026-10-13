package com.nms.askdashboard.model;

import java.util.List;
import java.util.Map;

/** {@code sql} has its parameters inlined, for reading only — the panel's "Show SQL". */
public record QueryResult(List<QueryColumn> columns, List<Map<String, Object>> rows, String sql, String anchor, Window window) {

  public record Window(String from, String to) {}
}
