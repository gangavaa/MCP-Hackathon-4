package com.nms.askdashboard.query;

import com.nms.askdashboard.security.NmsUser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The rows behind the dashboard's tenant and farm pages — tenants, their farms, a farm's devices and issues — read
 * as nms-core-ui's tenant dashboard and farm card show them. Fixed statements, not the semantic layer: these pages
 * list single farms and devices by id, which no aggregate query answers. Each statement is still confined to the
 * caller's tenants by {@link QueryBuilder#tenantClause}, so a farm outside them is simply not found.
 */
@Service
public class EstateService {

  /** A farm's open issues by severity, as the four columns the pages show. */
  private static final String OPEN_BY_SEVERITY = """
      count(*) FILTER (WHERE i.severity = 'CRITICAL')::int AS critical,
      count(*) FILTER (WHERE i.severity = 'HIGH')::int AS high,
      count(*) FILTER (WHERE i.severity = 'MEDIUM')::int AS medium,
      count(*) FILTER (WHERE i.severity = 'LOW')::int AS low""";

  private final JdbcTemplate jdbc;

  public EstateService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The tenants the user may see, with how many farms each has. */
  public List<Map<String, Object>> tenants(NmsUser user) {
    List<Object> params = new ArrayList<>();
    String scope = scope(user, params);
    return jdbc.queryForList("""
        SELECT t.id, t.name, t.environment, t.specie, t.color, count(f.id)::int AS farms
        FROM tenants t LEFT JOIN farms f ON f.tenant_id = t.id
        WHERE %s
        GROUP BY t.id ORDER BY t.name""".formatted(scope), params.toArray());
  }

  /** One tenant and every farm of it: connectivity, devices online and offline, open issues by severity. */
  public Map<String, Object> tenant(NmsUser user, String tenantId) {
    Map<String, Object> tenant = tenants(user).stream().filter(t -> tenantId.equals(t.get("id"))).findFirst()
        .orElseThrow(() -> new NotFoundException("No tenant " + tenantId + "."));
    List<Object> params = new ArrayList<>();
    String scope = scope(user, params);
    params.add(tenantId);
    List<Map<String, Object>> farms = jdbc.queryForList("""
        SELECT f.id, f.name, f.farm_name AS "farmName", f.country, f.address,
               f.connectivity_status AS "connectivityStatus", f.version, f.protocol, f.animals_on_farm AS animals,
               dv.devices, dv.offline AS "offlineDevices", dv.low_battery AS "lowBatteryDevices",
               iss.critical, iss.high, iss.medium, iss.low
        FROM farms f JOIN tenants t ON t.id = f.tenant_id
        LEFT JOIN LATERAL (
          SELECT count(*)::int AS devices,
                 count(*) FILTER (WHERE d.status = 'OFFLINE')::int AS offline,
                 count(*) FILTER (WHERE d.status = 'LOW_BATTERY')::int AS low_battery
          FROM devices d WHERE d.farm_id = f.id) dv ON TRUE
        LEFT JOIN LATERAL (
          SELECT %s
          FROM issues i WHERE i.farm_id = f.id AND i.resolved_at IS NULL) iss ON TRUE
        WHERE %s AND t.id = ?
        ORDER BY f.name""".formatted(OPEN_BY_SEVERITY, scope), params.toArray());
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("tenant", tenant);
    out.put("farms", farms);
    return out;
  }

  /** One farm, as its farm card shows it: identity, every device, its issues and 30 days of connectivity. */
  public Map<String, Object> farm(NmsUser user, String farmId) {
    List<Object> params = new ArrayList<>();
    String scope = scope(user, params);
    params.add(farmId);
    Map<String, Object> farm = jdbc.queryForList("""
        SELECT f.id, f.name, f.farm_name AS "farmName", f.specie, f.environment,
               f.connectivity_status AS "connectivityStatus", f.version, f.protocol, f.contract_user AS "contractUser",
               f.animals_on_farm AS animals, f.address, f.country, f.latitude, f.longitude,
               %s AS "registeredAt", %s AS "lastUpgradeAt",
               t.id AS "tenantId", t.name AS "tenantName", t.color AS "tenantColor"
        FROM farms f JOIN tenants t ON t.id = f.tenant_id
        WHERE %s AND f.id = ?""".formatted(iso("f.registered_at"), iso("f.last_upgrade_at"), scope), params.toArray())
        .stream().findFirst().orElseThrow(() -> new NotFoundException("No farm " + farmId + "."));

    List<Map<String, Object>> devices = jdbc.queryForList("""
        SELECT d.id, d.device_type AS type, d.hardware_type AS hardware, d.firmware_version AS firmware, d.status,
               d.battery_pct AS battery, %s AS "lastSeen",
               (SELECT count(*) FROM issues i WHERE i.resource_id = d.id AND i.resolved_at IS NULL)::int AS "openIssues"
        FROM devices d WHERE d.farm_id = ?
        ORDER BY d.status = 'ONLINE', d.device_type, d.id""".formatted(iso("d.last_seen_at")), farmId);

    List<Map<String, Object>> issues = jdbc.queryForList("""
        SELECT i.id, a.description, a.category, i.severity, i.alert_state AS state,
               %s AS "createdAt", %s AS "resolvedAt", i.resource_id AS "deviceId", d.device_type AS "deviceType"
        FROM issues i JOIN alert_definitions a ON a.id = i.definition_id LEFT JOIN devices d ON d.id = i.resource_id
        WHERE i.farm_id = ?
        ORDER BY i.resolved_at IS NOT NULL, %s, i.created_at DESC
        LIMIT 500""".formatted(iso("i.created_at"), iso("i.resolved_at"), severityOrder("i.severity")), farmId);

    List<Map<String, Object>> connectivity = jdbc.queryForList("""
        SELECT to_char(c.day, 'YYYY-MM-DD') AS day, c.online, c.offline, c.total
        FROM connectivity_snapshots c WHERE c.farm_id = ?
        ORDER BY c.day DESC LIMIT 30""", farmId);

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("farm", farm);
    out.put("devices", devices);
    out.put("issues", issues);
    out.put("connectivity", connectivity.reversed());
    return out;
  }

  private static String scope(NmsUser user, List<Object> params) {
    String clause = QueryBuilder.tenantClause(user.tenants(), params);
    return clause.isEmpty() ? "TRUE" : clause;
  }

  private static String iso(String column) {
    return "to_char(" + column + " AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"')";
  }

  private static String severityOrder(String column) {
    return "CASE " + column + " WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 WHEN 'LOW' THEN 4 ELSE 5 END";
  }
}
