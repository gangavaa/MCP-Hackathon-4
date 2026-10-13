package com.nms.askdashboard.tenant;

import com.nms.askdashboard.api.ApiException;
import com.nms.askdashboard.query.EstateService;
import com.nms.askdashboard.query.NotFoundException;
import com.nms.askdashboard.query.QueryService;
import com.nms.askdashboard.security.NmsUser;
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
 * Creates, edits and deletes tenants — admins only ({@link NmsUser#isAdmin()}). A tenant that still has farms is
 * not deleted: its farms, devices and issues would go with it. A farm's environment and specie are its tenant's, so
 * an edit to either carries over to the tenant's farms.
 */
@Service
public class TenantService {

  public static final Set<String> ENVIRONMENTS = new TreeSet<>(Set.of("Production", "Staging", "Development"));
  public static final Set<String> SPECIES = new TreeSet<>(Set.of("Dairy", "Feedlot", "Poultry"));
  static final String DEFAULT_COLOR = "#0075C3";
  private static final Pattern COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");
  private static final int MAX_NAME = 80;

  /** The body of a create (every field but color required) or an edit (any field; a missing one is unchanged). */
  public record TenantRequest(String name, String environment, String specie, String color) {}

  private static final Logger audit = LoggerFactory.getLogger("audit");

  private final JdbcTemplate jdbc;
  private final EstateService estate;
  private final QueryService queries;

  public TenantService(JdbcTemplate jdbc, EstateService estate, QueryService queries) {
    this.jdbc = jdbc;
    this.estate = estate;
    this.queries = queries;
  }

  @Transactional
  public Map<String, Object> create(NmsUser user, TenantRequest request) {
    user.requireAdmin();
    TenantRequest t = validated(request == null ? new TenantRequest(null, null, null, null) : request);
    String id = UUID.randomUUID().toString();
    write(t, () -> jdbc.update("INSERT INTO tenants (id, name, environment, specie, color) VALUES (?, ?, ?, ?, ?)",
        id, t.name(), t.environment(), t.specie(), t.color()));
    audit.info("user={} tenant-created id={} name={}", user.username(), id, t.name());
    return find(user, id);
  }

  @Transactional
  public Map<String, Object> update(NmsUser user, String id, TenantRequest changes) {
    user.requireAdmin();
    Map<String, Object> current = existing(id);
    TenantRequest c = changes == null ? new TenantRequest(null, null, null, null) : changes;
    TenantRequest t = validated(new TenantRequest(
        c.name() != null ? c.name() : (String) current.get("name"),
        c.environment() != null ? c.environment() : (String) current.get("environment"),
        c.specie() != null ? c.specie() : (String) current.get("specie"),
        c.color() != null ? c.color() : (String) current.get("color")));
    write(t, () -> jdbc.update("UPDATE tenants SET name = ?, environment = ?, specie = ?, color = ? WHERE id = ?",
        t.name(), t.environment(), t.specie(), t.color(), id));
    jdbc.update("UPDATE farms SET environment = ?, specie = ? WHERE tenant_id = ?", t.environment(), t.specie(), id);
    audit.info("user={} tenant-updated id={} name={}", user.username(), id, t.name());
    return find(user, id);
  }

  @Transactional
  public void delete(NmsUser user, String id) {
    user.requireAdmin();
    Map<String, Object> current = existing(id);
    int farms = jdbc.queryForObject("SELECT count(*) FROM farms WHERE tenant_id = ?", Integer.class, id);
    if (farms > 0) {
      throw new ApiException(HttpStatus.CONFLICT, "Tenant \"" + current.get("name") + "\" still has " + farms + " farm"
          + (farms == 1 ? "" : "s") + " — move or remove them before deleting it.");
    }
    jdbc.update("DELETE FROM tenants WHERE id = ?", id);
    queries.forgetCatalogues();
    audit.info("user={} tenant-deleted id={} name={}", user.username(), id, current.get("name"));
  }

  /** Trimmed and checked, every problem named at once; a missing color becomes the default. */
  static TenantRequest validated(TenantRequest r) {
    List<String> problems = new ArrayList<>();
    String name = r.name() == null ? "" : r.name().trim();
    if (name.isEmpty()) problems.add("name is required");
    else if (name.length() > MAX_NAME) problems.add("name is at most " + MAX_NAME + " characters");
    if (!ENVIRONMENTS.contains(r.environment())) problems.add("environment must be one of " + String.join(", ", ENVIRONMENTS));
    if (!SPECIES.contains(r.specie())) problems.add("specie must be one of " + String.join(", ", SPECIES));
    String color = r.color() == null ? DEFAULT_COLOR : r.color().trim();
    if (!COLOR.matcher(color).matches()) problems.add("color must be a hex colour such as #23885C");
    if (!problems.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid tenant: " + String.join("; ", problems) + ".");
    return new TenantRequest(name, r.environment(), r.specie(), color.toUpperCase());
  }

  /** Runs the insert or update; a name another tenant has is a 409. The value lists the model sees change with it. */
  private void write(TenantRequest t, Runnable statement) {
    try {
      statement.run();
    } catch (DuplicateKeyException e) {
      throw new ApiException(HttpStatus.CONFLICT, "A tenant named \"" + t.name() + "\" already exists.");
    }
    queries.forgetCatalogues();
  }

  private Map<String, Object> existing(String id) {
    return jdbc.queryForList("SELECT id, name, environment, specie, color FROM tenants WHERE id = ?", id).stream().findFirst()
        .orElseThrow(() -> new NotFoundException("No tenant " + id + "."));
  }

  private Map<String, Object> find(NmsUser user, String id) {
    return estate.tenants(user).stream().filter(t -> id.equals(t.get("id"))).findFirst()
        .orElseThrow(() -> new NotFoundException("No tenant " + id + "."));
  }
}
