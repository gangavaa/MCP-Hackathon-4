package com.nms.dashboard.analytics.web;

import com.nms.dashboard.analytics.application.QueryService;
import com.nms.dashboard.analytics.domain.ChartData;
import com.nms.dashboard.analytics.domain.ChartSpec;
import com.nms.dashboard.analytics.domain.DatasetInfo;
import com.nms.dashboard.analytics.domain.QueryRequest;
import com.nms.dashboard.analytics.domain.QueryResult;
import com.nms.dashboard.shared.security.CurrentUser;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The semantic query layer over REST — what the dashboard's charts and the test host ask, inside the user's tenants. */
@RestController
public class AnalyticsController {

  private final QueryService queries;
  private final CurrentUser currentUser;

  public AnalyticsController(QueryService queries, CurrentUser currentUser) {
    this.queries = queries;
    this.currentUser = currentUser;
  }

  @GetMapping("/api/datasets")
  public List<DatasetInfo> datasets() {
    return queries.datasets(currentUser.get());
  }

  @PostMapping("/api/query")
  public QueryResult query(@RequestBody QueryRequest request) {
    return queries.run(request, currentUser.get());
  }

  @PostMapping("/api/chart")
  public ChartData chart(@RequestBody ChartSpec spec) {
    return queries.chart(spec, currentUser.get());
  }
}
