package com.nms.dashboard.farm.infrastructure;

import com.nms.dashboard.farm.domain.ConnectivityStatus;
import com.nms.dashboard.farm.domain.Farm;
import com.nms.dashboard.farm.domain.FarmHoldings;
import com.nms.dashboard.farm.domain.FarmRepository;
import com.nms.dashboard.farm.domain.Location;
import com.nms.dashboard.farm.domain.Protocol;
import com.nms.dashboard.shared.error.ConflictException;
import com.nms.dashboard.tenant.domain.Environment;
import com.nms.dashboard.tenant.domain.Specie;
import com.nms.dashboard.tenant.domain.Tenant;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@link FarmRepository} over the {@code farms} table and the tables that hang off a farm (devices, issues,
 * connectivity snapshots). Its unique index on {@code lower(name)} keeps farm names apart; {@code issues.tenant_id}
 * copies the farm's tenant, so a move rewrites it.
 */
@Repository
public class JdbcFarmRepository implements FarmRepository {

  private final JdbcTemplate jdbc;

  public JdbcFarmRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Farm> findById(String id) {
    return jdbc.query("SELECT * FROM farms WHERE id = ?", JdbcFarmRepository::toFarm, id).stream().findFirst();
  }

  @Override
  public void add(Farm f) {
    uniquely(f, () -> jdbc.update("""
        INSERT INTO farms (id, tenant_id, template_id, name, farm_name, specie, environment, connectivity_status, version,
                           protocol, contract_user, animals_on_farm, address, country, latitude, longitude,
                           registered_at, last_upgrade_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        f.id(), f.tenantId(), f.templateId(), f.name(), f.farmName(), f.specie().label(), f.environment().label(),
        f.connectivity().name(), f.version(), f.protocol().label(), f.contractUser(), f.animals(), f.location().town(),
        f.location().country(), f.location().latitude(), f.location().longitude(), ts(f.registeredAt()), ts(f.lastUpgradeAt())));
  }

  @Override
  public void update(Farm f) {
    uniquely(f, () -> jdbc.update("""
        UPDATE farms SET tenant_id = ?, name = ?, farm_name = ?, specie = ?, environment = ?, connectivity_status = ?,
               version = ?, protocol = ?, contract_user = ?, animals_on_farm = ?, address = ?, country = ?,
               latitude = ?, longitude = ?, last_upgrade_at = ?
        WHERE id = ?""",
        f.tenantId(), f.name(), f.farmName(), f.specie().label(), f.environment().label(), f.connectivity().name(),
        f.version(), f.protocol().label(), f.contractUser(), f.animals(), f.location().town(), f.location().country(),
        f.location().latitude(), f.location().longitude(), ts(f.lastUpgradeAt()), f.id()));
    jdbc.update("UPDATE issues SET tenant_id = ? WHERE farm_id = ? AND tenant_id <> ?", f.tenantId(), f.id(), f.tenantId());
  }

  @Override
  public FarmHoldings holdings(String farmId) {
    return new FarmHoldings(count("devices", farmId), count("issues", farmId), count("connectivity_snapshots", farmId));
  }

  @Override
  public void removeWithHoldings(String farmId) {
    jdbc.update("DELETE FROM issues WHERE farm_id = ?", farmId);
    jdbc.update("DELETE FROM connectivity_snapshots WHERE farm_id = ?", farmId);
    jdbc.update("DELETE FROM devices WHERE farm_id = ?", farmId);
    jdbc.update("DELETE FROM farms WHERE id = ?", farmId);
  }

  @Override
  public int countOfTenant(String tenantId) {
    return jdbc.queryForObject("SELECT count(*) FROM farms WHERE tenant_id = ?", Integer.class, tenantId);
  }

  @Override
  public void reclassifyFarmsOf(Tenant tenant) {
    jdbc.update("UPDATE farms SET environment = ?, specie = ? WHERE tenant_id = ?",
        tenant.environment().label(), tenant.specie().label(), tenant.id());
  }

  private int count(String table, String farmId) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE farm_id = ?", Integer.class, farmId);
  }

  private static void uniquely(Farm f, Runnable statement) {
    try {
      statement.run();
    } catch (DuplicateKeyException e) {
      throw new ConflictException("A farm named \"" + f.name() + "\" already exists.");
    }
  }

  private static Farm toFarm(ResultSet rs, int row) throws SQLException {
    return new Farm(rs.getString("id"), rs.getString("tenant_id"), rs.getString("template_id"), rs.getString("name"),
        rs.getString("farm_name"), rs.getString("contract_user"),
        new Location(rs.getString("address"), rs.getString("country"), rs.getDouble("latitude"), rs.getDouble("longitude")),
        rs.getString("version"), Protocol.fromLabel(rs.getString("protocol")).orElseThrow(),
        ConnectivityStatus.fromLabel(rs.getString("connectivity_status")).orElseThrow(), rs.getInt("animals_on_farm"),
        Specie.fromLabel(rs.getString("specie")).orElseThrow(), Environment.fromLabel(rs.getString("environment")).orElseThrow(),
        instant(rs.getTimestamp("registered_at")), instant(rs.getTimestamp("last_upgrade_at")));
  }

  private static Timestamp ts(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant instant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
