package com.nms.dashboard.mcp.api;

/**
 * {@code POST /api/chart}'s answer, as far as this server reads it — to describe the rows to the model and draw the
 * PNG. The panel gets the API's JSON untouched. {@code previous} is present when the spec compares periods.
 */
public record ChartData(ChartSpec spec, QueryResult result, QueryResult previous) {}
