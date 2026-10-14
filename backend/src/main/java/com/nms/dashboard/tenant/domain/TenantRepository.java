package com.nms.dashboard.tenant.domain;

import java.util.Optional;

/** Where tenants are kept. A name another tenant has, in any case, is refused on {@link #add} and {@link #update}. */
public interface TenantRepository {

  Optional<Tenant> findById(String id);

  void add(Tenant tenant);

  void update(Tenant tenant);

  void remove(String id);
}
