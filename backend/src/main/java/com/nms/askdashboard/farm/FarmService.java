package com.nms.askdashboard.farm;

import com.nms.askdashboard.api.ApiException;
import com.nms.askdashboard.query.EstateService;
import com.nms.askdashboard.query.NotFoundException;
import com.nms.askdashboard.query.QueryService;
import com.nms.askdashboard.security.NmsUser;
import com.nms.askdashboard.seed.EstateGenerator;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates, edits and deletes farms — admins only ({@link NmsUser#isAdmin()}).
 *
 * <ul>
 *   <li>A farm's specie and environment are its tenant's: set from the tenant, never from the request.
 *   <li>Moving a farm to another tenant (an edit of {@code tenantId}) moves its issues with it.
 *   <li>A new version counts as an upgrade: {@code lastUpgradeAt} becomes the data's "now".
 *   <li>A farm that has devices, issues or connectivity history is deleted only with {@code cascade} — they go with it.
 * </ul>
 */
@Service
public class FarmService {

  public static final Set<String> CONNECTIVITY = new TreeSet<>(Set.of("ONLINE", "PARTIALLY_ONLINE", "WARNING", "OFFLINE", "UNKNOWN"));
  public static final Set<String> PROTOCOLS = new TreeSet<>(Set.of("IoT", "NoIoT"));
  private static final Pattern VERSION = Pattern.compile("\\d+(\\.\\d+){1,3}");
  private static final int MAX_TEXT = 120;
  private static final int MAX_ANIMALS = 1_000_000;

  /**
   * A create (tenantId, name, address, country, latitude, longitude and version required; the rest defaulted) or an
   * edit (any field; a missing one is unchanged). {@code address} is the farm's town, as the pages show it.
   */
  public record FarmRequest(String tenantId, String name, String farmName, String contractUser, String address,
      String country, Double latitude, Double longitude, String version, String protocol, String connectivityStatus,
      Integer animals) {}

  private static final Logger audit = LoggerFactory.getLogger("audit");

  private final JdbcTemplate jdbc;
  private final EstateService estate;
  private final QueryService queries;

  public FarmService(JdbcTemplate jdbc, EstateService estate, QueryService queries) {
    this.jdbc = jdbc;
    this.estate = estate;
    this.queries = queries;
  }

  @Transactional
  public Map<String, Object> create(NmsUser user, FarmRequest request) {
    user.requireAdmin();
    FarmRequest r = request == null ? empty() : request;
    FarmRequest f = validated(new FarmRequest(r.tenantId(), r.name(), orEmpty(r.farmName()), orEmpty(r.contractUser()),
        r.address(), r.country(), r.latitude(), r.longitude(), r.version(),
        r.protocol() != null ? r.protocol() : "IoT",
        r.connectivityStatus() != null ? r.connectivityStatus() : "UNKNOWN", // nothing has reported yet
        r.animals() != null ? r.animals() : 0));
    Map<String, Object> tenant = tenant(f.tenantId());
    String id = "farm-" + UUID.randomUUID();
    write(f, () -> jdbc.update("""
        INSERT INTO farms (id, tenant_id, template_id, name, farm_name, specie, environment, connectivity_status, version,
                           protocol, contract_user, animals_on_farm, address, country, latitude, longitude, registered_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        id, f.tenantId(), EstateGenerator.FARM_TEMPLATE_ID, f.name(), f.farmName(), tenant.get("specie"), tenant.get("environment"),
        f.connectivityStatus(), f.version(), f.protocol(), f.contractUser(), f.animals(), f.address(), f.country(),
        f.latitude(), f.longitude(), Timestamp.from(queries.anchor())));
    audit.info("user={} farm-created id={} name={} tenant={}", user.username(), id, f.name(), f.tenantId());
    return find(user, id);
  }

  @Transactional
  public Map<String, Object> update(NmsUser user, String id, FarmRequest changes) {
    user.requireAdmin();
    Map<String, Object> current = existing(id);
    FarmRequest c = changes == null ? empty() : changes;
    FarmRequest f = validated(new FarmRequest(
        or(c.tenantId(), current.get("tenant_id")), or(c.name(), current.get("name")),
        or(c.farmName(), current.get("farm_name")), or(c.contractUser(), current.get("contract_user")),
        or(c.address(), current.get("address")), or(c.country(), current.get("country")),
        c.latitude() != null ? c.latitude() : (Double) current.get("latitude"),
        c.longitude() != null ? c.longitude() : (Double) current.get("longitude"),
        or(c.version(), current.get("version")), or(c.protocol(), current.get("protocol")),
        or(c.connectivityStatus(), current.get("connectivity_status")),
        c.animals() != null ? c.animals() : (Integer) current.get("animals_on_farm")));
    Map<String, Object> tenant = tenant(f.tenantId());
    boolean upgraded = !f.version().equals(current.get("version"));
    write(f, () -> jdbc.update("""
        UPDATE farms SET tenant_id = ?, name = ?, farm_name = ?, specie = ?, environment = ?, connectivity_status = ?,
               version = ?, protocol = ?, contract_user = ?, animals_on_farm = ?, address = ?, country = ?,
               latitude = ?, longitude = ?, last_upgrade_at = CASE WHEN ?::boolean THEN ?::timestamptz ELSE last_upgrade_at END
        WHERE id = ?""",
        f.tenantId(), f.name(), f.farmName(), tenant.get("specie"), tenant.get("environment"), f.connectivityStatus(),
        f.version(), f.protocol(), f.contractUser(), f.animals(), f.address(), f.country(), f.latitude(), f.longitude(),
        upgraded, Timestamp.from(queries.anchor()), id));
    if (!f.tenantId().equals(current.get("tenant_id"))) {
      jdbc.update("UPDATE issues SET tenant_id = ? WHERE farm_id = ?", f.tenantId(), id); // the issues move with the farm
    }
    audit.info("user={} farm-updated id={} name={} tenant={}", user.username(), id, f.name(), f.tenantId());
    return find(user, id);
  }

  /** Without {@code cascade}, a farm that still holds data is refused — the answer says how much would go. */
  @Transactional
  public void delete(NmsUser user, String id, boolean cascade) {
    user.requireAdmin();
    Map<String, Object> current = existing(id);
    int devices = count("devices", id), issues = count("issues", id), days = count("connectivity_snapshots", id);
    if (!cascade && devices + issues + days > 0) {
      throw new ApiException(HttpStatus.CONFLICT, "Farm \"" + current.get("name") + "\" still has " + devices + " devices, " + issues
          + " issues and " + days + " days of connectivity history. Delete with ?cascade=true to remove them with the farm.");
    }
    jdbc.update("DELETE FROM issues WHERE farm_id = ?", id);
    jdbc.update("DELETE FROM connectivity_snapshots WHERE farm_id = ?", id);
    jdbc.update("DELETE FROM devices WHERE farm_id = ?", id);
    jdbc.update("DELETE FROM farms WHERE id = ?", id);
    queries.forgetCatalogues();
    audit.info("user={} farm-deleted id={} name={} devices={} issues={} days={}", user.username(), id, current.get("name"), devices, issues, days);
  }

  /** Trimmed and checked, every problem named at once. */
  static FarmRequest validated(FarmRequest r) {
    List<String> problems = new ArrayList<>();
    String tenantId = trim(r.tenantId());
    if (tenantId.isEmpty()) problems.add("tenantId is required");
    String name = required("name", r.name(), problems);
    String address = required("address", r.address(), problems);
    String country = required("country", r.country(), problems);
    String farmName = optional("farmName", r.farmName(), problems);
    String contractUser = optional("contractUser", r.contractUser(), problems);
    if (r.latitude() == null || r.latitude() < -90 || r.latitude() > 90) problems.add("latitude must be between -90 and 90");
    if (r.longitude() == null || r.longitude() < -180 || r.longitude() > 180) problems.add("longitude must be between -180 and 180");
    String version = trim(r.version());
    if (!VERSION.matcher(version).matches()) problems.add("version must look like 7.6.0.1");
    if (!PROTOCOLS.contains(r.protocol())) problems.add("protocol must be one of " + String.join(", ", PROTOCOLS));
    if (!CONNECTIVITY.contains(r.connectivityStatus())) problems.add("connectivityStatus must be one of " + String.join(", ", CONNECTIVITY));
    if (r.animals() == null || r.animals() < 0 || r.animals() > MAX_ANIMALS) problems.add("animals must be between 0 and " + MAX_ANIMALS);
    if (!problems.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid farm: " + String.join("; ", problems) + ".");
    return new FarmRequest(tenantId, name, farmName, contractUser, address, country, r.latitude(), r.longitude(), version,
        r.protocol(), r.connectivityStatus(), r.animals());
  }

  // ------------------------------------------------------------------------------------------- internals

  private static String required(String field, String value, List<String> problems) {
    String v = trim(value);
    if (v.isEmpty()) problems.add(field + " is required");
    else if (v.length() > MAX_TEXT) problems.add(field + " is at most " + MAX_TEXT + " characters");
    return v;
  }

  private static String optional(String field, String value, List<String> problems) {
    String v = trim(value);
    if (v.length() > MAX_TEXT) problems.add(field + " is at most " + MAX_TEXT + " characters");
    return v;
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  private static String or(String value, Object current) {
    return value != null ? value : (String) current;
  }

  private static FarmRequest empty() {
    return new FarmRequest(null, null, null, null, null, null, null, null, null, null, null, null);
  }

  /** Runs the insert or update; a name another farm has is a 409. The value lists the model sees change with it. */
  private void write(FarmRequest f, Runnable statement) {
    try {
      statement.run();
    } catch (DuplicateKeyException e) {
      throw new ApiException(HttpStatus.CONFLICT, "A farm named \"" + f.name() + "\" already exists.");
    }
    queries.forgetCatalogues();
  }

  /** The farm's tenant — an unknown one is the request's fault, so a 400 rather than a 404. */
  private Map<String, Object> tenant(String tenantId) {
    return jdbc.queryForList("SELECT id, specie, environment FROM tenants WHERE id = ?", tenantId).stream().findFirst()
        .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Invalid farm: no tenant " + tenantId + "."));
  }

  private Map<String, Object> existing(String id) {
    return jdbc.queryForList("SELECT * FROM farms WHERE id = ?", id).stream().findFirst()
        .orElseThrow(() -> new NotFoundException("No farm " + id + "."));
  }

  private int count(String table, String farmId) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE farm_id = ?", Integer.class, farmId);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> find(NmsUser user, String id) {
    return (Map<String, Object>) estate.farm(user, id).get("farm");
  }
}
