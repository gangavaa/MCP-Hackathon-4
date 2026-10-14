package com.nms.dashboard.analytics.domain;

/** {@code kind} is "dimension" or "metric". */
public record QueryColumn(String key, String label, String kind) {}
