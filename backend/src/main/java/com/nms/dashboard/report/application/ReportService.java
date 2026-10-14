package com.nms.dashboard.report.application;

import com.nms.dashboard.report.domain.Report;
import com.nms.dashboard.report.domain.ReportCriteria;
import com.nms.dashboard.report.domain.ReportInfo;
import com.nms.dashboard.report.domain.ReportParam;
import com.nms.dashboard.report.domain.ReportResult;
import com.nms.dashboard.shared.domain.EstateClock;
import com.nms.dashboard.shared.security.NmsUser;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Answers the fixed reports, inside the user's tenants. Checks the parameters against the report, places the window
 * on the estate's clock, and runs the report's one statement — the dashboard, the chat panel and the MCP server all
 * get the same answer to the same report.
 */
@Service
public class ReportService {

  private final ReportQueries queries;
  private final EstateClock clock;

  public ReportService(ReportQueries queries, EstateClock clock) {
    this.queries = queries;
    this.clock = clock;
  }

  public ReportResult run(Report report, Map<String, String> params, NmsUser user) {
    ReportCriteria criteria = ReportCriteria.parse(report, params);
    Instant anchor = clock.now();
    Instant from = null;
    Instant to = null;
    ReportResult.Window window = null;
    if (criteria.days() != null) {
      to = anchor.minus(Duration.ofDays(criteria.offsetDays()));
      from = to.minus(Duration.ofDays(criteria.days()));
      window = new ReportResult.Window(day(from), day(to.minusMillis(1)));
    }
    List<Map<String, Object>> rows = queries.rows(report, criteria, from, to, user.tenants());
    return new ReportResult(report.id(), report.title(), report.columns(), rows, criteria.asParams(report), anchor.toString(), window);
  }

  /** Every report, with the values its parameters may hold inside the user's tenants. */
  public List<ReportInfo> catalogue(NmsUser user) {
    Map<ReportParam, List<String>> values = new EnumMap<>(ReportParam.class);
    for (ReportParam param : ReportParam.values()) values.put(param, queries.values(param, user.tenants()));
    return Arrays.stream(Report.values()).map(report -> new ReportInfo(
        report.id(),
        "/api/reports/" + report.id(),
        report.title(),
        report.description(),
        report.chartType(),
        report.params().stream().map(p -> new ReportInfo.Param(p.key(), p.description(), defaultOf(report, p),
            values.get(p).isEmpty() ? null : values.get(p))).toList(),
        report.columns(),
        report.drill() == null ? null : new ReportInfo.Drill(report.drill().param().key(), report.drill().report())))
        .toList();
  }

  private static Integer defaultOf(Report report, ReportParam param) {
    return switch (param) {
      case DAYS -> report.defaultDays();
      case LIMIT -> report.defaultLimit();
      default -> null;
    };
  }

  private static String day(Instant instant) {
    return instant.toString().substring(0, 10);
  }
}
