package com.nms.dashboard.analytics.application;

import com.nms.dashboard.analytics.domain.ChartData;
import com.nms.dashboard.analytics.domain.ChartSpec;
import com.nms.dashboard.analytics.domain.DatasetCatalog;
import com.nms.dashboard.analytics.domain.DatasetInfo;
import com.nms.dashboard.analytics.domain.QueryException;
import com.nms.dashboard.analytics.domain.QueryRequest;
import com.nms.dashboard.analytics.domain.QueryResult;
import com.nms.dashboard.analytics.domain.TimeRange;
import com.nms.dashboard.analytics.domain.DatasetCatalog.Dataset;
import com.nms.dashboard.analytics.domain.DatasetCatalog.Field;
import com.nms.dashboard.analytics.infrastructure.QueryBuilder;
import com.nms.dashboard.shared.domain.EstateChanged;
import com.nms.dashboard.shared.persistence.TenantFilter;
import com.nms.dashboard.shared.security.NmsUser;
import com.nms.dashboard.shared.security.TenantScope;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Runs questions against Postgres, and describes the catalogue — with real dimension values — to its callers. Every
 * method takes the user asking: what they see is confined to their tenants.
 */
@Service
public class QueryService {

  private final JdbcTemplate jdbc;
  /** Per tenant scope: a Dairy user is offered Dairy's countries and farms, not the whole estate's. */
  private final Map<String, List<DatasetInfo>> catalogues = new ConcurrentHashMap<>();

  public QueryService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** "Now" for every relative time range — stored with the seed, so answers don't drift with the calendar. */
  public Instant anchor() {
    return jdbc.queryForObject("SELECT anchor FROM meta LIMIT 1", java.sql.Timestamp.class).toInstant();
  }

  public QueryResult run(QueryRequest req, NmsUser user) {
    Instant anchor = anchor();
    QueryBuilder.Built built = QueryBuilder.build(req, anchor, user.tenants());
    List<Map<String, Object>> rows = jdbc.queryForList(built.sql(), built.params().toArray());
    return new QueryResult(built.columns(), rows, QueryBuilder.inline(built.sql(), built.params()), anchor.toString(), built.window());
  }

  /** The chart's query, and the same query one window earlier when it compares. */
  public ChartData chart(ChartSpec spec, NmsUser user) {
    if (spec == null || spec.query() == null) throw new QueryException("A chart needs a query.");
    QueryResult result = run(spec.query(), user);
    if (!spec.comparesPreviousPeriod()) return new ChartData(spec, result, null);
    TimeRange range = spec.query().timeRange();
    QueryResult previous = run(spec.query().withTimeRange(new TimeRange(range.lastDays(), range.offset() + range.lastDays())), user);
    return new ChartData(spec, result, previous);
  }

  public List<DatasetInfo> datasets(NmsUser user) {
    return catalogues.computeIfAbsent(user.tenants().key(), key -> {
      List<DatasetInfo> out = new ArrayList<>();
      DatasetCatalog.DATASETS.forEach((id, ds) -> out.add(describe(id, ds, user.tenants())));
      return List.copyOf(out);
    });
  }

  /** After a re-seed the values may differ. */
  public void forgetCatalogues() {
    catalogues.clear();
  }

  /** A tenant or farm was added, renamed or removed: the dimension values offered are read again — once it committed. */
  @TransactionalEventListener(fallbackExecution = true)
  void onEstateChanged(EstateChanged event) {
    forgetCatalogues();
  }

  public Map<String, String> drill(String dataset) {
    Dataset ds = DatasetCatalog.DATASETS.get(dataset);
    return ds == null ? Map.of() : ds.drill();
  }

  private DatasetInfo describe(String id, Dataset ds, TenantScope scope) {
    List<DatasetInfo.Field> dimensions = new ArrayList<>();
    ds.dimensions().forEach((key, field) -> dimensions.add(new DatasetInfo.Field(key, field.label(), field.description(), valuesOf(ds, field, scope))));
    List<DatasetInfo.Field> metrics = new ArrayList<>();
    ds.metrics().forEach((key, field) -> metrics.add(new DatasetInfo.Field(key, field.label(), field.description(), null)));
    return new DatasetInfo(id, ds.label(), ds.description(), ds.timeColumn() == null ? null : ds.timeColumn().label(), dimensions, metrics, ds.drill());
  }

  /** The values a dimension holds, when there are 30 or fewer — so filters use exact spellings. */
  private List<String> valuesOf(Dataset ds, Field field, TenantScope scope) {
    if (!field.listValues()) return null;
    List<Object> params = new ArrayList<>();
    String tenants = TenantFilter.clause(scope, params);
    List<String> values = jdbc.queryForList(
        "SELECT DISTINCT " + field.sql() + " AS v FROM " + ds.from() + " WHERE " + field.sql() + " IS NOT NULL"
            + (tenants.isEmpty() ? "" : " AND " + tenants) + " ORDER BY 1 LIMIT 31", String.class, params.toArray());
    return values.size() <= 30 ? values : null;
  }
}
