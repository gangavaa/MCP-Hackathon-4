package com.nms.dashboard.farm.application;

import com.nms.dashboard.farm.domain.Farm;
import com.nms.dashboard.farm.domain.FarmChanged;
import com.nms.dashboard.farm.domain.FarmDetails;
import com.nms.dashboard.farm.domain.FarmHoldings;
import com.nms.dashboard.farm.domain.FarmRepository;
import com.nms.dashboard.seed.EstateGenerator;
import com.nms.dashboard.shared.domain.EstateClock;
import com.nms.dashboard.shared.error.ConflictException;
import com.nms.dashboard.shared.error.NotFoundException;
import com.nms.dashboard.shared.security.NmsUser;
import com.nms.dashboard.tenant.domain.Tenant;
import com.nms.dashboard.tenant.domain.TenantRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The farm use cases — admins only ({@link NmsUser#isAdmin()}). Moving a farm (an edit of its tenant) moves its
 * issues with it. A farm that holds devices, issues or connectivity history is deleted only with {@code cascade}.
 */
@Service
public class FarmAdministration {

  private static final Logger audit = LoggerFactory.getLogger("audit");

  private final FarmRepository farms;
  private final TenantRepository tenants;
  private final EstateClock clock;
  private final ApplicationEventPublisher events;

  public FarmAdministration(FarmRepository farms, TenantRepository tenants, EstateClock clock, ApplicationEventPublisher events) {
    this.farms = farms;
    this.tenants = tenants;
    this.clock = clock;
    this.events = events;
  }

  @Transactional
  public Farm register(NmsUser user, FarmDetails details) {
    user.requireAdmin();
    FarmDetails d = details == null ? FarmDetails.NONE : details;
    Farm farm = Farm.register("farm-" + UUID.randomUUID(), EstateGenerator.FARM_TEMPLATE_ID, d, tenantOrNull(d.tenantId()), clock.now());
    farms.add(farm);
    events.publishEvent(new FarmChanged(farm.id()));
    audit.info("user={} farm-created id={} name={} tenant={}", user.username(), farm.id(), farm.name(), farm.tenantId());
    return farm;
  }

  @Transactional
  public Farm edit(NmsUser user, String id, FarmDetails changes) {
    user.requireAdmin();
    Farm before = existing(id);
    FarmDetails c = changes == null ? FarmDetails.NONE : changes;
    String tenantId = c.tenantId() != null ? c.tenantId().trim() : before.tenantId();
    Farm after = before.edit(c, tenantOrNull(tenantId), clock.now());
    farms.update(after);
    events.publishEvent(new FarmChanged(id));
    audit.info("user={} farm-updated id={} name={} tenant={}{}", user.username(), id, after.name(), after.tenantId(),
        after.movedFrom(before) ? " moved-from=" + before.tenantId() : "");
    return after;
  }

  /** Without {@code cascade}, a farm that still holds data is refused — the answer says how much would go. */
  @Transactional
  public void delete(NmsUser user, String id, boolean cascade) {
    user.requireAdmin();
    Farm farm = existing(id);
    FarmHoldings h = farms.holdings(id);
    if (!cascade && !h.isEmpty()) {
      throw new ConflictException("Farm \"" + farm.name() + "\" still has " + h.devices() + " devices, " + h.issues() + " issues and "
          + h.connectivityDays() + " days of connectivity history. Delete with ?cascade=true to remove them with the farm.");
    }
    farms.removeWithHoldings(id);
    events.publishEvent(new FarmChanged(id));
    audit.info("user={} farm-deleted id={} name={} devices={} issues={} days={}", user.username(), id, farm.name(),
        h.devices(), h.issues(), h.connectivityDays());
  }

  private Farm existing(String id) {
    return farms.findById(id).orElseThrow(() -> new NotFoundException("No farm " + id + "."));
  }

  /** The farm's tenant, or null when there is none by that id — the farm then names that as a problem. */
  private Tenant tenantOrNull(String tenantId) {
    return tenantId == null || tenantId.isBlank() ? null : tenants.findById(tenantId.trim()).orElse(null);
  }
}
