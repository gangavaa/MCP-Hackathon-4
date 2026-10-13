package com.nms.askdashboard.model;

/** {@code kind} is "dimension" or "metric". */
public record QueryColumn(String key, String label, String kind) {}
