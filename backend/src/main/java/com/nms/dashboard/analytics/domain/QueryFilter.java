package com.nms.dashboard.analytics.domain;

/** One filter: {@code value} is a string, a number, or a list of them for {@code in}. */
public record QueryFilter(String field, String op, Object value) {}
