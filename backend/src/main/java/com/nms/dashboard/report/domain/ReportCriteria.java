package com.nms.dashboard.report.domain;

import com.nms.dashboard.shared.domain.Problems;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The parameters of one report call, checked against what that report takes: a parameter it doesn't take, a number
 * out of range or an unknown severity is refused — every problem named at once, with the parameters it does take.
 * Left-out {@code days} and {@code limit} take the report's defaults. A blank value counts as left out.
 */
public record ReportCriteria(String tenant, String country, String farm, String firmware, String issueType,
    String severity, Integer days, int offsetDays, Integer limit) {

  static final List<String> SEVERITIES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");
  private static final int MAX_TEXT = 200;

  public static ReportCriteria parse(Report report, Map<String, String> raw) {
    Problems problems = new Problems(report.id() + " request");
    Map<ReportParam, String> given = new LinkedHashMap<>();
    raw.forEach((key, value) -> {
      ReportParam param = ReportParam.byKey(key).filter(report::takes).orElse(null);
      if (param == null) {
        problems.add(key + " is not a parameter of " + report.id() + " (it takes " + takes(report) + ")");
      } else {
        String v = problems.optional(key, value, MAX_TEXT);
        if (!v.isEmpty()) given.put(param, v);
      }
    });

    String severity = given.containsKey(ReportParam.SEVERITY) ? given.get(ReportParam.SEVERITY).toUpperCase(Locale.ROOT) : null;
    if (severity != null && !SEVERITIES.contains(severity)) problems.add("severity must be one of " + String.join(", ", SEVERITIES));
    Integer days = number(problems, given, ReportParam.DAYS, 1, ReportParam.MAX_DAYS);
    Integer offset = number(problems, given, ReportParam.OFFSET_DAYS, 0, ReportParam.MAX_DAYS);
    Integer limit = number(problems, given, ReportParam.LIMIT, 1, ReportParam.MAX_LIMIT);
    problems.throwIfAny();

    return new ReportCriteria(given.get(ReportParam.TENANT), given.get(ReportParam.COUNTRY), given.get(ReportParam.FARM),
        given.get(ReportParam.FIRMWARE), given.get(ReportParam.ISSUE_TYPE), severity,
        days != null ? days : report.defaultDays(), offset != null ? offset : 0,
        limit != null ? limit : report.defaultLimit());
  }

  /** What the report ran with, defaults filled in — echoed with its rows, so a chart can say which window it shows. */
  public Map<String, Object> asParams(Report report) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (ReportParam param : report.params()) {
      Object value = switch (param) {
        case TENANT -> tenant;
        case COUNTRY -> country;
        case FARM -> farm;
        case FIRMWARE -> firmware;
        case ISSUE_TYPE -> issueType;
        case SEVERITY -> severity;
        case DAYS -> days;
        case OFFSET_DAYS -> days == null || offsetDays == 0 ? null : offsetDays;
        case LIMIT -> limit;
      };
      if (value != null) out.put(param.key(), value);
    }
    return out;
  }

  static String takes(Report report) {
    return report.params().isEmpty() ? "no parameters" : report.params().stream().map(ReportParam::key).collect(Collectors.joining(", "));
  }

  private static Integer number(Problems problems, Map<ReportParam, String> given, ReportParam param, int min, int max) {
    String value = given.get(param);
    if (value == null) return null;
    try {
      int n = Integer.parseInt(value);
      if (n >= min && n <= max) return n;
    } catch (NumberFormatException notANumber) {
      // reported below
    }
    problems.add(param.key() + " must be a whole number from " + min + " to " + max);
    return null;
  }
}
