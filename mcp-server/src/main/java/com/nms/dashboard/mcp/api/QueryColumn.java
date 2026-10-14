package com.nms.dashboard.mcp.api;

/** {@code kind} is "dimension" or "metric". */
public record QueryColumn(String key, String label, String kind) {}
