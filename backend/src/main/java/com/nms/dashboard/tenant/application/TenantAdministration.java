package com.nms.dashboard.tenant.application;

import com.nms.dashboard.farm.domain.FarmRepository;
import com.nms.dashboard.shared.error.ConflictException;
import com.nms.dashboard.shared.error.NotFoundException;
import com.nms.dashboard.shared.security.NmsUser;
import com.nms.dashboard.tenant.domain.Tenant;
import com.nms.dashboard.tenant.domain.TenantChanged;
import com.nms.dashboard.tenant.domain.TenantRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tenant use cases — admins only ({@link NmsUser#isAdmin()}). A tenant that still has farms is not deleted: its
 * farms, devices and issues would go with it. A change of environment or specie carries over to the tenant's farms.
 */
@Service
public class TenantAdministration {

  /** What a tenant is created with, or edited with — in an edit, a null field is left as it is. */
  public record TenantDetails(String name, String environment, String specie, String color) {

    public static final TenantDetails NONE = new TenantDetails(null, null, null, null);
  }

  private static final Logger audit = LoggerFactory.getLogger("audit");

  private final TenantRepository tenants;
  private final FarmRepository farms;
  private final ApplicationEventPublisher events;

  public TenantAdministration(TenantRepository tenants, FarmRepository farms, ApplicationEventPublisher events) {
    this.tenants = tenants;
    this.farms = farms;
    this.events = events;
  }

  @Transactional
  public Tenant create(NmsUser user, TenantDetails details) {
    user.requireAdmin();
    TenantDetails d = details == null ? TenantDetails.NONE : details;
    Tenant tenant = Tenant.create(UUID.randomUUID().toString(), d.name(), d.environment(), d.specie(), d.color());
    tenants.add(tenant);
    events.publishEvent(new TenantChanged(tenant.id()));
    audit.info("user={} tenant-created id={} name={}", user.username(), tenant.id(), tenant.name());
    return tenant;
  }

  @Transactional
  public Tenant edit(NmsUser user, String id, TenantDetails changes) {
    user.requireAdmin();
    Tenant before = existing(id);
    TenantDetails c = changes == null ? TenantDetails.NONE : changes;
    Tenant after = before.edit(c.name(), c.environment(), c.specie(), c.color());
    tenants.update(after);
    if (after.classifiesFarmsDifferentlyFrom(before)) farms.reclassifyFarmsOf(after);
    events.publishEvent(new TenantChanged(id));
    audit.info("user={} tenant-updated id={} name={}", user.username(), id, after.name());
    return after;
  }

  @Transactional
  public void delete(NmsUser user, String id) {
    user.requireAdmin();
    Tenant tenant = existing(id);
    int count = farms.countOfTenant(id);
    if (count > 0) {
      throw new ConflictException("Tenant \"" + tenant.name() + "\" still has " + count + " farm" + (count == 1 ? "" : "s")
          + " — move or remove them before deleting it.");
    }
    tenants.remove(id);
    events.publishEvent(new TenantChanged(id));
    audit.info("user={} tenant-deleted id={} name={}", user.username(), id, tenant.name());
  }

  private Tenant existing(String id) {
    return tenants.findById(id).orElseThrow(() -> new NotFoundException("No tenant " + id + "."));
  }
}
