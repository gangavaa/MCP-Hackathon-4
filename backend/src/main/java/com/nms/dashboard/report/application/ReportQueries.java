package com.nms.dashboard.report.application;

import com.nms.dashboard.report.domain.Report;
import com.nms.dashboard.report.domain.ReportCriteria;
import com.nms.dashboard.report.domain.ReportParam;
import com.nms.dashboard.shared.security.TenantScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Runs the reports' fixed statements. Every one is confined to the tenant scope it is given. */
public interface ReportQueries {

  /** The report's rows. {@code from} and {@code to} bound its window, or are both null for all history. */
  List<Map<String, Object>> rows(Report report, ReportCriteria criteria, Instant from, Instant to, TenantScope scope);

  /** The values a parameter may hold inside the scope; empty when it has too many to list (farm) or none (days). */
  List<String> values(ReportParam param, TenantScope scope);
}
