package com.nms.dashboard.tenant.infrastructure;

import com.nms.dashboard.shared.error.ConflictException;
import com.nms.dashboard.tenant.domain.Environment;
import com.nms.dashboard.tenant.domain.Specie;
import com.nms.dashboard.tenant.domain.Tenant;
import com.nms.dashboard.tenant.domain.TenantRepository;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@link TenantRepository} over the {@code tenants} table; its unique index on {@code lower(name)} keeps names apart. */
@Repository
public class JdbcTenantRepository implements TenantRepository {

  private final JdbcTemplate jdbc;

  public JdbcTenantRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Tenant> findById(String id) {
    return jdbc.query("SELECT id, name, environment, specie, color FROM tenants WHERE id = ?", (rs, row) -> new Tenant(
        rs.getString("id"), rs.getString("name"),
        Environment.fromLabel(rs.getString("environment")).orElseThrow(),
        Specie.fromLabel(rs.getString("specie")).orElseThrow(),
        rs.getString("color")), id).stream().findFirst();
  }

  @Override
  public void add(Tenant t) {
    uniquely(t, () -> jdbc.update("INSERT INTO tenants (id, name, environment, specie, color) VALUES (?, ?, ?, ?, ?)",
        t.id(), t.name(), t.environment().label(), t.specie().label(), t.color()));
  }

  @Override
  public void update(Tenant t) {
    uniquely(t, () -> jdbc.update("UPDATE tenants SET name = ?, environment = ?, specie = ?, color = ? WHERE id = ?",
        t.name(), t.environment().label(), t.specie().label(), t.color(), t.id()));
  }

  @Override
  public void remove(String id) {
    jdbc.update("DELETE FROM tenants WHERE id = ?", id);
  }

  private static void uniquely(Tenant t, Runnable statement) {
    try {
      statement.run();
    } catch (DuplicateKeyException e) {
      throw new ConflictException("A tenant named \"" + t.name() + "\" already exists.");
    }
  }
}
