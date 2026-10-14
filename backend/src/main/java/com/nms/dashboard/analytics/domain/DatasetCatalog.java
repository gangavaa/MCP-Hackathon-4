package com.nms.dashboard.analytics.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The semantic layer: every dataset, dimension and metric a request may name, and the SQL each stands for. Nothing
 * outside this file ever becomes part of a SQL string — {@link QueryBuilder} looks identifiers up here and sends
 * every caller value as a parameter.
 */
public final class DatasetCatalog {

  /** {@code orderSql}: sort key when ordering by this dimension. {@code listValues}: list its values in the catalogue. */
  public record Field(String label, String sql, String description, String orderSql, boolean listValues) {

    static Field of(String label, String sql) {
      return new Field(label, sql, null, null, true);
    }

    static Field unlisted(String label, String sql) {
      return new Field(label, sql, null, null, false);
    }

    Field describedAs(String text) {
      return new Field(label, sql, text, orderSql, listValues);
    }

    Field orderedBy(String order) {
      return new Field(label, sql, description, order, listValues);
    }
  }

  public record TimeColumn(String sql, String label) {}

  public record Dataset(
      String label,
      String description,
      String from,
      TimeColumn timeColumn,
      Map<String, Field> dimensions,
      Map<String, Field> metrics,
      Map<String, Field> numericFilters,
      Map<String, String> drill) {}

  private static String severityOrder(String column) {
    return "CASE " + column + " WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 WHEN 'LOW' THEN 4 ELSE 5 END";
  }

  /* The dimensions every dataset reaches through its farm. */
  private static Map<String, Field> farmDimensions() {
    Map<String, Field> m = new LinkedHashMap<>();
    m.put("tenant", Field.of("Tenant", "t.name"));
    m.put("environment", Field.of("Environment", "f.environment"));
    m.put("specie", Field.of("Species", "f.specie"));
    m.put("country", Field.of("Country", "f.country"));
    m.put("town", Field.unlisted("Town", "f.address"));
    m.put("farm", Field.unlisted("Farm", "f.name"));
    return m;
  }

  @SafeVarargs
  private static <V> Map<String, V> ordered(Map.Entry<String, V>... entries) {
    Map<String, V> m = new LinkedHashMap<>();
    for (Map.Entry<String, V> e : entries) m.put(e.getKey(), e.getValue());
    return m;
  }

  private static <V> Map<String, V> with(Map<String, V> base, Map<String, V> more) {
    Map<String, V> m = new LinkedHashMap<>(base);
    m.putAll(more);
    return m;
  }

  public static final Map<String, Dataset> DATASETS = ordered(
      Map.entry("farms", new Dataset(
          "Farms",
          "Every farm in the estate, one row each: where it is, its tenant, its current connectivity.",
          "farms f JOIN tenants t ON t.id = f.tenant_id",
          new TimeColumn("f.registered_at", "registration date"),
          with(farmDimensions(), ordered(
              Map.entry("connectivity_status", Field.of("Connectivity", "f.connectivity_status")),
              Map.entry("version", Field.of("Farm server version", "f.version")),
              Map.entry("protocol", Field.of("Protocol", "f.protocol")))),
          ordered(
              Map.entry("farm_count", Field.of("Farms", "count(*)::int")),
              Map.entry("animals", Field.of("Animals", "sum(f.animals_on_farm)::int")),
              Map.entry("avg_animals", Field.of("Avg animals per farm", "round(avg(f.animals_on_farm))::int"))),
          ordered(Map.entry("animals_on_farm", Field.of("Animals on farm", "f.animals_on_farm"))),
          ordered(
              Map.entry("tenant", "country"), Map.entry("environment", "tenant"), Map.entry("specie", "country"),
              Map.entry("country", "farm"), Map.entry("connectivity_status", "farm"), Map.entry("version", "farm"),
              Map.entry("protocol", "farm")))),

      Map.entry("devices", new Dataset(
          "Devices",
          "Every device (collars, ear tags, gateways, sensors) with its CURRENT status, firmware and battery. Status is a "
              + "snapshot of now, not a history — use `connectivity` for trends over time.",
          "devices d JOIN farms f ON f.id = d.farm_id JOIN tenants t ON t.id = f.tenant_id",
          new TimeColumn("d.last_seen_at", "last seen"),
          with(farmDimensions(), ordered(
              Map.entry("device_type", Field.of("Device type", "d.device_type")),
              Map.entry("hardware", Field.of("Hardware", "d.hardware_type")),
              Map.entry("firmware", Field.of("Firmware", "d.firmware_version")),
              Map.entry("status", Field.of("Status", "d.status")))),
          ordered(
              Map.entry("device_count", Field.of("Devices", "count(*)::int")),
              Map.entry("offline_count", Field.of("Offline devices", "(count(*) FILTER (WHERE d.status = 'OFFLINE'))::int")),
              Map.entry("offline_pct", Field.of("Offline %",
                  "round(100.0 * count(*) FILTER (WHERE d.status = 'OFFLINE') / nullif(count(*), 0), 1)::float8")),
              Map.entry("low_battery_count", Field.of("Low-battery devices", "(count(*) FILTER (WHERE d.status = 'LOW_BATTERY'))::int")),
              Map.entry("avg_battery", Field.of("Avg battery %", "round(avg(d.battery_pct))::int"))),
          ordered(Map.entry("battery_pct", Field.of("Battery %", "d.battery_pct"))),
          ordered(
              Map.entry("tenant", "country"), Map.entry("environment", "tenant"), Map.entry("specie", "country"),
              Map.entry("country", "farm"), Map.entry("town", "farm"), Map.entry("farm", "firmware"), Map.entry("firmware", "farm"),
              Map.entry("device_type", "firmware"), Map.entry("hardware", "farm"), Map.entry("status", "farm")))),

      Map.entry("issues", new Dataset(
          "Issues",
          "Alerts raised on devices over the last 60 days: severity, type, the farm and device, when it was raised and resolved.",
          "issues i JOIN alert_definitions a ON a.id = i.definition_id JOIN farms f ON f.id = i.farm_id "
              + "JOIN tenants t ON t.id = i.tenant_id LEFT JOIN devices d ON d.id = i.resource_id",
          new TimeColumn("i.created_at", "raised at"),
          with(farmDimensions(), ordered(
              Map.entry("severity", Field.of("Severity", "i.severity").orderedBy(severityOrder("i.severity"))),
              Map.entry("issue_type", Field.of("Issue type", "a.description")),
              Map.entry("category", Field.of("Category", "a.category")),
              Map.entry("alert_state", Field.of("State", "i.alert_state").describedAs("OPEN or CLOSED")),
              Map.entry("device_type", Field.of("Device type", "d.device_type")),
              Map.entry("firmware", Field.of("Device firmware", "d.firmware_version")))),
          ordered(
              Map.entry("issue_count", Field.of("Issues", "count(*)::int")),
              Map.entry("open_count", Field.of("Open issues", "(count(*) FILTER (WHERE i.resolved_at IS NULL))::int")),
              Map.entry("critical_count", Field.of("Critical issues", "(count(*) FILTER (WHERE i.severity = 'CRITICAL'))::int")),
              Map.entry("avg_resolution_hours", Field.of("Avg hours to resolve",
                  "round((avg(extract(epoch FROM (i.resolved_at - i.created_at))) / 3600)::numeric, 1)::float8"))),
          Map.of(),
          ordered(
              Map.entry("tenant", "country"), Map.entry("environment", "tenant"), Map.entry("specie", "country"),
              Map.entry("country", "farm"), Map.entry("town", "farm"), Map.entry("farm", "issue_type"), Map.entry("issue_type", "farm"),
              Map.entry("severity", "issue_type"), Map.entry("category", "issue_type"), Map.entry("alert_state", "issue_type"),
              Map.entry("device_type", "issue_type"), Map.entry("firmware", "issue_type")))),

      Map.entry("connectivity", new Dataset(
          "Connectivity history",
          "One snapshot per farm per day for 60 days: how many of its devices were online and offline. Use with timeBucket for trends.",
          "connectivity_snapshots c JOIN farms f ON f.id = c.farm_id JOIN tenants t ON t.id = f.tenant_id",
          new TimeColumn("c.day", "day"),
          farmDimensions(),
          ordered(
              Map.entry("online_pct", Field.of("Online %", "round(100.0 * sum(c.online) / nullif(sum(c.total), 0), 1)::float8")),
              Map.entry("offline_pct", Field.of("Offline %", "round(100.0 * sum(c.offline) / nullif(sum(c.total), 0), 1)::float8")),
              Map.entry("offline_devices", Field.of("Offline devices", "sum(c.offline)::int")
                  .describedAs("Summed over the days in the group — bucket by day to read it as \"offline that day\".")),
              Map.entry("avg_offline_devices", Field.of("Avg offline devices per farm-day", "round(avg(c.offline), 1)::float8"))),
          Map.of(),
          ordered(
              Map.entry("tenant", "country"), Map.entry("environment", "tenant"), Map.entry("specie", "country"),
              Map.entry("country", "farm"), Map.entry("town", "farm")))));

  private DatasetCatalog() {}
}
