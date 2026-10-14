package com.nms.dashboard.platform.web;

import com.nms.dashboard.estate.application.EstateQueries;
import com.nms.dashboard.shared.domain.EstateClock;
import com.nms.dashboard.shared.security.CurrentUser;
import com.nms.dashboard.shared.security.NmsUser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** What every page needs about the app itself: whether it is up, who is signed in, and the built chat panel. */
@RestController
public class PlatformController {

  private final EstateClock clock;
  private final EstateQueries estate;
  private final CurrentUser currentUser;
  private final DataSource dataSource;
  private final Path panelFile;

  public PlatformController(EstateClock clock, EstateQueries estate, CurrentUser currentUser, DataSource dataSource,
      @Value("${app.panel-file}") String panelFile) {
    this.clock = clock;
    this.estate = estate;
    this.currentUser = currentUser;
    this.dataSource = dataSource;
    this.panelFile = Path.of(panelFile);
  }

  @GetMapping("/api/health")
  public Map<String, Object> health() {
    String db = dataSource.getClass().getName().contains("Hikari") ? "postgres" : "embedded-postgres";
    return Map.of("ok", true, "db", db, "anchor", clock.now().toString());
  }

  /** Who is signed in, which tenants they see (by name), and what they may do. */
  @GetMapping("/api/me")
  public Map<String, Object> me() {
    NmsUser user = currentUser.get();
    List<String> tenants = user.tenants().all()
        ? List.of("All tenants")
        : estate.tenants(user).stream().map(t -> (String) t.get("name")).toList();
    Map<String, Object> me = new LinkedHashMap<>();
    me.put("username", user.username());
    me.put("name", user.name());
    me.put("tenants", tenants);
    me.put("canWrite", user.canWrite());
    me.put("admin", user.isAdmin());
    return me;
  }

  /** The chat panel's build, for the dashboard's test host (/host), which renders it as a chat would. Holds no data. */
  @GetMapping(path = "/panel.html", produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<String> panel() throws IOException {
    if (!Files.exists(panelFile)) return ResponseEntity.status(404).body("The panel is not built — run npm run build in frontend/.");
    return ResponseEntity.ok(Files.readString(panelFile));
  }
}
