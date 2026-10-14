package com.nms.dashboard.analytics.infrastructure;

import com.nms.dashboard.analytics.domain.DatasetCatalog;
import com.nms.dashboard.analytics.domain.QueryColumn;
import com.nms.dashboard.analytics.domain.QueryFilter;
import com.nms.dashboard.analytics.domain.QueryRequest;
import com.nms.dashboard.analytics.domain.QueryException;
import com.nms.dashboard.analytics.domain.QueryResult;
import com.nms.dashboard.analytics.domain.DatasetCatalog.Dataset;
import com.nms.dashboard.analytics.domain.DatasetCatalog.Field;
import com.nms.dashboard.shared.persistence.TenantFilter;
import com.nms.dashboard.shared.security.TenantScope;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Turns a {@link QueryRequest} into one parameterised statement. Identifiers come only from {@link DatasetCatalog};
 * every value the caller sends travels as a {@code ?} parameter. An unknown name is a {@link QueryException} that
 * lists the known ones.
 *
 * <p>Every statement is confined to the caller's {@link TenantScope} — taken from their access token, never from the
 * request — so a question can only ever see what the person asking may see.
 */
public final class QueryBuilder {

  public record Built(String sql, List<Object> params, List<QueryColumn> columns, QueryResult.Window window) {}

  private QueryBuilder() {}

  public static Built build(QueryRequest req, Instant anchor, TenantScope scope) {
    Dataset ds = DatasetCatalog.DATASETS.get(req.dataset());
    if (ds == null) throw new QueryException("Unknown dataset '" + req.dataset() + "'. Available: " + keys(DatasetCatalog.DATASETS.keySet()) + ".");

    Field metric = ds.metrics().get(req.metric());
    if (metric == null) throw new QueryException("Unknown metric '" + req.metric() + "' for " + req.dataset() + ". Available: " + keys(ds.metrics().keySet()) + ".");

    List<String> groupBy = req.groupByOrEmpty();
    if (groupBy.size() > 2) throw new QueryException("groupBy takes at most two dimensions (category, then series).");
    Map<String, Field> dims = new LinkedHashMap<>();
    for (String key : groupBy) {
      Field field = ds.dimensions().get(key);
      if (field == null) throw new QueryException("Unknown dimension '" + key + "' for " + req.dataset() + ". Available: " + keys(ds.dimensions().keySet()) + ".");
      dims.put(key, field);
    }

    List<Object> params = new ArrayList<>();
    List<String> select = new ArrayList<>();
    List<QueryColumn> columns = new ArrayList<>();
    boolean bucketed = req.timeBucket() != null;
    if (bucketed) {
      if (ds.timeColumn() == null) throw new QueryException(req.dataset() + " has no time column to bucket.");
      String unit = "week".equals(req.timeBucket()) ? "week" : "day";
      select.add("to_char(date_trunc('" + unit + "', " + ds.timeColumn().sql() + "), 'YYYY-MM-DD') AS \"period\"");
      columns.add(new QueryColumn("period", unit.equals("week") ? "Week" : "Day", "dimension"));
    }
    dims.forEach((key, field) -> {
      select.add(field.sql() + " AS \"" + key + "\"");
      columns.add(new QueryColumn(key, field.label(), "dimension"));
    });
    select.add(metric.sql() + " AS \"" + req.metric() + "\"");
    columns.add(new QueryColumn(req.metric(), metric.label(), "metric"));

    List<String> where = new ArrayList<>();
    where.add(TenantFilter.clause(scope, params)); // first, before anything the caller sent
    for (QueryFilter filter : req.filtersOrEmpty()) {
      Field field = ds.dimensions().containsKey(filter.field()) ? ds.dimensions().get(filter.field()) : ds.numericFilters().get(filter.field());
      if (field == null) {
        List<String> all = new ArrayList<>(ds.dimensions().keySet());
        all.addAll(ds.numericFilters().keySet());
        throw new QueryException("Unknown filter field '" + filter.field() + "' for " + req.dataset() + ". Available: " + keys(all) + ".");
      }
      List<?> values = filter.value() instanceof Collection<?> c ? List.copyOf(c) : java.util.Collections.singletonList(filter.value());
      String op = filter.op() == null ? "" : filter.op();
      switch (op) {
        case "eq" -> where.add(field.sql() + " = " + param(params, values.get(0)));
        case "neq" -> where.add(field.sql() + " <> " + param(params, values.get(0)));
        case "in" -> {
          if (values.isEmpty()) throw new QueryException("Filter '" + filter.field() + "' with op 'in' needs at least one value.");
          where.add(field.sql() + " IN (" + values.stream().map(v -> param(params, v)).collect(Collectors.joining(", ")) + ")");
        }
        case "contains" -> where.add(field.sql() + "::text ILIKE " + param(params, "%" + values.get(0) + "%"));
        case "gte" -> where.add(field.sql() + " >= " + param(params, values.get(0)));
        case "lte" -> where.add(field.sql() + " <= " + param(params, values.get(0)));
        default -> throw new QueryException("Unknown filter op '" + op + "'. Use eq, neq, in, contains, gte or lte.");
      }
    }

    QueryResult.Window window = null;
    if (req.timeRange() != null) {
      if (ds.timeColumn() == null) throw new QueryException(req.dataset() + " has no time column for a timeRange.");
      int lastDays = req.timeRange().lastDays();
      if (lastDays < 1 || lastDays > 366) throw new QueryException("timeRange.lastDays must be between 1 and 366.");
      Instant to = anchor.minus(Duration.ofDays(req.timeRange().offset()));
      Instant from = to.minus(Duration.ofDays(lastDays));
      where.add(ds.timeColumn().sql() + " >= " + param(params, OffsetDateTime.ofInstant(from, ZoneOffset.UTC)));
      where.add(ds.timeColumn().sql() + " < " + param(params, OffsetDateTime.ofInstant(to, ZoneOffset.UTC)));
      window = new QueryResult.Window(from.toString().substring(0, 10), to.minusMillis(1).toString().substring(0, 10));
    }

    int dimCount = select.size() - 1;
    int firstDim = bucketed ? 2 : 1;
    List<String> byDimension = new ArrayList<>();
    int position = firstDim;
    for (Field field : dims.values()) {
      byDimension.add(field.orderSql() != null ? field.orderSql() : String.valueOf(position));
      position++;
    }
    List<String> order = new ArrayList<>();
    if (bucketed) order.add("1");
    if ("dimension".equals(req.order()) || bucketed) {
      order.addAll(byDimension);
    } else if (dimCount > 0) {
      order.add((dimCount + 1) + ("metric_asc".equals(req.order()) ? " ASC" : " DESC") + " NULLS LAST");
      order.addAll(byDimension);
    }

    int limit = Math.min(Math.max(1, req.limit() != null ? req.limit() : bucketed ? 400 : 25), 500);

    List<String> sql = new ArrayList<>();
    sql.add("SELECT " + String.join(", ", select));
    sql.add("FROM " + ds.from());
    where.removeIf(String::isEmpty);
    if (!where.isEmpty()) sql.add("WHERE " + String.join(" AND ", where));
    if (dimCount > 0) sql.add("GROUP BY " + IntStream.rangeClosed(1, dimCount).mapToObj(String::valueOf).collect(Collectors.joining(", ")));
    if (!order.isEmpty()) sql.add("ORDER BY " + String.join(", ", order));
    if (dimCount > 0) sql.add("LIMIT " + limit);

    return new Built(String.join("\n", sql), params, columns, window);
  }

  /** For display only — never executed. */
  public static String inline(String sql, List<Object> params) {
    StringBuilder out = new StringBuilder();
    int p = 0;
    for (char ch : sql.toCharArray()) {
      if (ch == '?' && p < params.size()) {
        Object value = params.get(p++);
        out.append(value instanceof Number ? value.toString() : "'" + String.valueOf(value).replace("'", "''") + "'");
      } else {
        out.append(ch);
      }
    }
    return out.toString();
  }

  private static String param(List<Object> params, Object value) {
    params.add(value);
    return "?";
  }

  private static String keys(Collection<String> keys) {
    return String.join(", ", keys);
  }
}
