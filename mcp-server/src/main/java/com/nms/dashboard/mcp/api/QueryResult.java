package com.nms.dashboard.mcp.api;

import java.util.List;
import java.util.Map;

/** {@code POST /api/query}'s answer: the columns, the rows, and the window and data date they cover. */
public record QueryResult(List<QueryColumn> columns, List<Map<String, Object>> rows, String anchor, Window window) {

  public record Window(String from, String to) {}
}
