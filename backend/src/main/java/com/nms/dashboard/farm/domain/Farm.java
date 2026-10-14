package com.nms.dashboard.farm.domain;

import com.nms.dashboard.shared.domain.Problems;
import com.nms.dashboard.tenant.domain.Environment;
import com.nms.dashboard.tenant.domain.Specie;
import com.nms.dashboard.tenant.domain.Tenant;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A farm of a tenant. Its rules:
 *
 * <ul>
 *   <li>its specie and environment are its tenant's — taken from the tenant, never set on the farm;
 *   <li>a new version is an upgrade, dated by the estate's clock;
 *   <li>a farm is registered {@link ConnectivityStatus#UNKNOWN} unless told otherwise — nothing has reported yet.
 * </ul>
 *
 * Immutable: {@link #register} and {@link #edit} return a valid farm or name every problem.
 */
public record Farm(String id, String tenantId, String templateId, String name, String farmName, String contractUser,
    Location location, String version, Protocol protocol, ConnectivityStatus connectivity, int animals, Specie specie,
    Environment environment, Instant registeredAt, Instant lastUpgradeAt) {

  static final int MAX_TEXT = 120;
  static final int MAX_ANIMALS = 1_000_000;
  private static final Pattern VERSION = Pattern.compile("\\d+(\\.\\d+){1,3}");

  /** A new farm of {@code tenant}, registered now. {@code details.tenantId} must name that tenant. */
  public static Farm register(String id, String templateId, FarmDetails d, Tenant tenant, Instant now) {
    FarmDetails withDefaults = new FarmDetails(d.tenantId(), d.name(), orEmpty(d.farmName()), orEmpty(d.contractUser()),
        d.address(), d.country(), d.latitude(), d.longitude(), d.version(),
        d.protocol() != null ? d.protocol() : Protocol.IOT.label(),
        d.connectivityStatus() != null ? d.connectivityStatus() : ConnectivityStatus.UNKNOWN.name(),
        d.animals() != null ? d.animals() : 0);
    return validated(id, templateId, withDefaults, tenant, now, null);
  }

  /** This farm with the changes; {@code tenant} is the farm's tenant after them (its own, or the one it moves to). */
  public Farm edit(FarmDetails c, Tenant tenant, Instant now) {
    FarmDetails merged = new FarmDetails(
        c.tenantId() != null ? c.tenantId() : tenantId,
        c.name() != null ? c.name() : name,
        c.farmName() != null ? c.farmName() : farmName,
        c.contractUser() != null ? c.contractUser() : contractUser,
        c.address() != null ? c.address() : location.town(),
        c.country() != null ? c.country() : location.country(),
        c.latitude() != null ? c.latitude() : location.latitude(),
        c.longitude() != null ? c.longitude() : location.longitude(),
        c.version() != null ? c.version() : version,
        c.protocol() != null ? c.protocol() : protocol.label(),
        c.connectivityStatus() != null ? c.connectivityStatus() : connectivity.name(),
        c.animals() != null ? c.animals() : animals);
    Farm edited = validated(id, templateId, merged, tenant, registeredAt, lastUpgradeAt);
    return edited.version.equals(version) ? edited : edited.upgradedAt(now);
  }

  public boolean movedFrom(Farm before) {
    return !tenantId.equals(before.tenantId);
  }

  private Farm upgradedAt(Instant when) {
    return new Farm(id, tenantId, templateId, name, farmName, contractUser, location, version, protocol, connectivity, animals,
        specie, environment, registeredAt, when);
  }

  private static Farm validated(String id, String templateId, FarmDetails d, Tenant tenant, Instant registeredAt, Instant lastUpgradeAt) {
    Problems problems = new Problems("farm");
    String tenantId = d.tenantId() == null ? "" : d.tenantId().trim();
    if (tenantId.isEmpty()) problems.add("tenantId is required");
    else if (tenant == null || !tenant.id().equals(tenantId)) problems.add("no tenant " + tenantId);
    String name = problems.required("name", d.name(), MAX_TEXT);
    String town = problems.required("address", d.address(), MAX_TEXT);
    String country = problems.required("country", d.country(), MAX_TEXT);
    String farmName = problems.optional("farmName", d.farmName(), MAX_TEXT);
    String contractUser = problems.optional("contractUser", d.contractUser(), MAX_TEXT);
    if (d.latitude() == null || d.latitude() < -90 || d.latitude() > 90) problems.add("latitude must be between -90 and 90");
    if (d.longitude() == null || d.longitude() < -180 || d.longitude() > 180) problems.add("longitude must be between -180 and 180");
    String version = d.version() == null ? "" : d.version().trim();
    if (!VERSION.matcher(version).matches()) problems.add("version must look like 7.6.0.1");
    Optional<Protocol> protocol = Protocol.fromLabel(d.protocol());
    if (protocol.isEmpty()) problems.add("protocol must be one of " + Protocol.labels());
    Optional<ConnectivityStatus> connectivity = ConnectivityStatus.fromLabel(d.connectivityStatus());
    if (connectivity.isEmpty()) problems.add("connectivityStatus must be one of " + ConnectivityStatus.labels());
    if (d.animals() == null || d.animals() < 0 || d.animals() > MAX_ANIMALS) problems.add("animals must be between 0 and " + MAX_ANIMALS);
    problems.throwIfAny();
    return new Farm(id, tenantId, templateId, name, farmName, contractUser, new Location(town, country, d.latitude(), d.longitude()),
        version, protocol.get(), connectivity.get(), d.animals(), tenant.specie(), tenant.environment(),
        Objects.requireNonNull(registeredAt), lastUpgradeAt);
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }
}
