package com.nms.dashboard.farm.domain;

import com.nms.dashboard.tenant.domain.Tenant;
import java.util.Optional;

/** Where farms are kept. A name another farm has, in any case, is refused on {@link #add} and {@link #update}. */
public interface FarmRepository {

  Optional<Farm> findById(String id);

  void add(Farm farm);

  /** Saves the farm; when it moved to another tenant, its issues move with it. */
  void update(Farm farm);

  FarmHoldings holdings(String farmId);

  /** Deletes the farm and everything it holds. */
  void removeWithHoldings(String farmId);

  int countOfTenant(String tenantId);

  /** Every farm of the tenant takes its specie and environment again. */
  void reclassifyFarmsOf(Tenant tenant);
}
