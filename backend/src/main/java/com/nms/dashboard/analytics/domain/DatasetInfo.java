package com.nms.dashboard.analytics.domain;

import java.util.List;
import java.util.Map;

/** A dataset as the catalogue describes it. {@code drill}: which dimension a click on a category opens next. */
public record DatasetInfo(
    String id,
    String label,
    String description,
    String timeColumn,
    List<Field> dimensions,
    List<Field> metrics,
    Map<String, String> drill) {

  /** {@code values}: the values a dimension holds, when there are few enough to list. */
  public record Field(String key, String label, String description, List<String> values) {}
}
