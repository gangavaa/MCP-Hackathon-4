package com.nms.askdashboard.api;

import com.nms.askdashboard.farm.FarmService;
import com.nms.askdashboard.farm.FarmService.FarmRequest;
import com.nms.askdashboard.model.ChartData;
import com.nms.askdashboard.model.ChartSpec;
import com.nms.askdashboard.model.DatasetInfo;
import com.nms.askdashboard.model.QueryRequest;
import com.nms.askdashboard.model.QueryResult;
import com.nms.askdashboard.query.EstateService;
import com.nms.askdashboard.query.QueryService;
import com.nms.askdashboard.security.CurrentUser;
import com.nms.askdashboard.security.NmsUser;
import com.nms.askdashboard.tenant.TenantService;
import com.nms.askdashboard.tenant.TenantService.TenantRequest;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The REST API the Angular dashboard and test host read. Every call acts as the signed-in user ({@link CurrentUser}):
 * their tenants bound what it sees. The dashboard is a fixed page built from /api/query and /api/chart; nothing is
 * pinned to it.
 */
@RestController
public class ApiController {

  private final QueryService queries;
  private final EstateService estate;
  private final TenantService tenantAdmin;
  private final FarmService farmAdmin;
  private final CurrentUser currentUser;
  private final DataSource dataSource;
  private final JdbcTemplate jdbc;
  private final Path panelFile;

  public ApiController(QueryService queries, EstateService estate, TenantService tenantAdmin, FarmService farmAdmin,
      CurrentUser currentUser, DataSource dataSource, JdbcTemplate jdbc, @Value("${app.panel-file}") String panelFile) {
    this.queries = queries;
    this.estate = estate;
    this.tenantAdmin = tenantAdmin;
    this.farmAdmin = farmAdmin;
    this.currentUser = currentUser;
    this.dataSource = dataSource;
    this.jdbc = jdbc;
    this.panelFile = Path.of(panelFile);
  }

  @GetMapping("/api/health")
  public Map<String, Object> health() {
    String db = dataSource.getClass().getName().contains("Hikari") ? "postgres" : "embedded-postgres";
    return Map.of("ok", true, "db", db, "anchor", queries.anchor().toString());
  }

  /** Who is signed in, which tenants they see (by name), and whether they may change the dashboard. */
  @GetMapping("/api/me")
  public Map<String, Object> me() {
    NmsUser user = currentUser.get();
    List<String> tenants = user.tenants().all()
        ? List.of("All tenants")
        : user.tenants().ids().isEmpty() ? List.of()
        : jdbc.queryForList("SELECT name FROM tenants WHERE id IN (" + String.join(",", user.tenants().ids().stream().map(id -> "?").toList())
            + ") ORDER BY name", String.class, user.tenants().ids().toArray());
    Map<String, Object> me = new LinkedHashMap<>();
    me.put("username", user.username());
    me.put("name", user.name());
    me.put("tenants", tenants);
    me.put("canWrite", user.canWrite());
    return me;
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

  @GetMapping("/api/tenants")
  public List<Map<String, Object>> tenants() {
    return estate.tenants(currentUser.get());
  }

  /** The tenant page: the tenant and every farm of it. */
  @GetMapping("/api/tenants/{id}")
  public Map<String, Object> tenant(@PathVariable String id) {
    return estate.tenant(currentUser.get(), id);
  }

  /** Admin only. Body: name, environment, specie, and optionally color. */
  @PostMapping("/api/tenants")
  public ResponseEntity<Map<String, Object>> createTenant(@RequestBody(required = false) TenantRequest request) {
    Map<String, Object> created = tenantAdmin.create(currentUser.get(), request);
    return ResponseEntity.created(URI.create("/api/tenants/" + created.get("id"))).body(created);
  }

  /** Admin only. Any of name, environment, specie, color; what is left out stays as it is. */
  @PatchMapping("/api/tenants/{id}")
  public Map<String, Object> updateTenant(@PathVariable String id, @RequestBody(required = false) TenantRequest changes) {
    return tenantAdmin.update(currentUser.get(), id, changes);
  }

  /** Admin only. Refused (409) while the tenant still has farms. */
  @DeleteMapping("/api/tenants/{id}")
  public ResponseEntity<Void> deleteTenant(@PathVariable String id) {
    tenantAdmin.delete(currentUser.get(), id);
    return ResponseEntity.noContent().build();
  }

  /** The farm page: the farm, its devices, its issues and 30 days of connectivity. */
  @GetMapping("/api/farms/{id}")
  public Map<String, Object> farm(@PathVariable String id) {
    return estate.farm(currentUser.get(), id);
  }

  /** Admin only. Body: tenantId, name, address, country, latitude, longitude, version; the rest is optional. */
  @PostMapping("/api/farms")
  public ResponseEntity<Map<String, Object>> createFarm(@RequestBody(required = false) FarmRequest request) {
    Map<String, Object> created = farmAdmin.create(currentUser.get(), request);
    return ResponseEntity.created(URI.create("/api/farms/" + created.get("id"))).body(created);
  }

  /** Admin only. Any farm field, tenantId included (moves the farm); what is left out stays as it is. */
  @PatchMapping("/api/farms/{id}")
  public Map<String, Object> updateFarm(@PathVariable String id, @RequestBody(required = false) FarmRequest changes) {
    return farmAdmin.update(currentUser.get(), id, changes);
  }

  /** Admin only. A farm with devices, issues or history is refused (409) unless {@code ?cascade=true}. */
  @DeleteMapping("/api/farms/{id}")
  public ResponseEntity<Void> deleteFarm(@PathVariable String id, @RequestParam(defaultValue = "false") boolean cascade) {
    farmAdmin.delete(currentUser.get(), id, cascade);
    return ResponseEntity.noContent().build();
  }

  /** The built MCP App, for the local test host — Claude Desktop gets it over MCP instead. Holds no data. */
  @GetMapping(path = "/panel.html", produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<String> panel() throws IOException {
    if (!Files.exists(panelFile)) return ResponseEntity.status(404).body("The panel is not built — run npm run build in frontend/.");
    return ResponseEntity.ok(Files.readString(panelFile));
  }
}
