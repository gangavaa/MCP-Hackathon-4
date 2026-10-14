package com.nms.dashboard.mcp.api;

/** The chart the API ran: what it is called, how to draw it, and the parts of its query the PNG labels. */
public record ChartSpec(String title, String chartType, Query query, String insight) {

  public record Query(String dataset, TimeRange timeRange) {}

  public record TimeRange(Integer lastDays) {}
}
