package com.nms.askdashboard.model;

/** {@code previous} is the same query one window earlier, present when the spec compares. */
public record ChartData(ChartSpec spec, QueryResult result, QueryResult previous) {}
