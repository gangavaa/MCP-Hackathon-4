package com.nms.askdashboard.seed;

import com.nms.askdashboard.query.QueryService;
import com.nms.askdashboard.seed.EstateGenerator.Estate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the estate on first start (Flyway has created the tables by then), and again — dropping pinned widgets too —
 * when started with {@code --reset}. Re-seed on the morning of a demo so the anchor reads as today. Dashboards start
 * over too: each user gets the default widgets again on their next visit.
 */
@Service
@Order(0)
public class SeedService implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(SeedService.class);

  private final JdbcTemplate jdbc;
  private final QueryService queries;
  private final int seed;

  public SeedService(JdbcTemplate jdbc, QueryService queries, @Value("${app.seed}") int seed) {
    this.jdbc = jdbc;
    this.queries = queries;
    this.seed = seed;
  }

  @Override
  public void run(ApplicationArguments args) {
    boolean empty = jdbc.queryForObject("SELECT count(*) FROM meta", Integer.class) == 0;
    if (args.containsOption("reset") || empty) {
      Estate estate = reseed(seed, EstateGenerator.startOfTodayUtc());
      log.info("Seeded the estate (seed {}, anchor {}): {} farms, {} devices, {} issues, {} connectivity snapshots",
          seed, estate.anchor().toString().substring(0, 10), estate.farms().size(), estate.devices().size(),
          estate.issues().size(), estate.snapshots().size());
    }
  }

  @Transactional
  public Estate reseed(int seed, Instant anchor) {
    jdbc.execute("TRUNCATE dashboard_widgets, dashboard_owners, connectivity_snapshots, issues, alert_definitions, devices, farms, tenants, meta");
    queries.forgetCatalogues();
    Estate estate = EstateGenerator.generate(seed, anchor);

    jdbc.update("INSERT INTO meta (anchor, seed) VALUES (?, ?)", ts(estate.anchor()), seed);
    batch("INSERT INTO tenants (id, name, environment, specie, color) VALUES (?, ?, ?, ?, ?)",
        EstateGenerator.TENANTS.stream().map(t -> new Object[] {t.id(), t.name(), t.environment(), t.specie(), t.color()}).toList());
    batch("""
        INSERT INTO farms (id, tenant_id, template_id, name, farm_name, specie, environment, connectivity_status, version,
          protocol, contract_user, animals_on_farm, address, country, latitude, longitude, registered_at, last_upgrade_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        estate.farms().stream().map(f -> new Object[] {f.id, f.tenantId, f.templateId, f.name, f.farmName, f.specie, f.environment,
            f.connectivityStatus, f.version, f.protocol, f.contractUser, f.animalsOnFarm, f.address, f.country, f.latitude,
            f.longitude, ts(f.registeredAt), ts(f.lastUpgradeAt)}).toList());
    batch("""
        INSERT INTO devices (id, farm_id, device_type, hardware_type, firmware_version, status, battery_pct, last_seen_at, registered_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        estate.devices().stream().map(d -> new Object[] {d.id(), d.farmId(), d.deviceType(), d.hardwareType(), d.firmwareVersion(),
            d.status(), d.batteryPct(), ts(d.lastSeenAt()), ts(d.registeredAt())}).toList());
    batch("INSERT INTO alert_definitions (id, description, severity, category) VALUES (?, ?, ?, ?)",
        EstateGenerator.DEFINITIONS.stream().map(d -> new Object[] {d.id(), d.description(), d.severity(), d.category()}).toList());
    batch("""
        INSERT INTO issues (id, definition_id, tenant_id, farm_id, resource_id, severity, alert_state, created_at, resolved_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        estate.issues().stream().map(i -> new Object[] {i.id(), i.definitionId(), i.tenantId(), i.farmId(), i.resourceId(),
            i.severity(), i.alertState(), ts(i.createdAt()), ts(i.resolvedAt())}).toList());
    batch("INSERT INTO connectivity_snapshots (farm_id, day, online, offline, total) VALUES (?, ?, ?, ?, ?)",
        estate.snapshots().stream().map(s -> new Object[] {s.farmId(), java.sql.Date.valueOf(s.day()), s.online(), s.offline(), s.total()}).toList());
    return estate;
  }

  private void batch(String sql, List<Object[]> rows) {
    jdbc.batchUpdate(sql, rows);
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }
}
