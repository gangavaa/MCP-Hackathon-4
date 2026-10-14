package com.nms.dashboard.estate.application;

import com.nms.dashboard.shared.error.NotFoundException;
import com.nms.dashboard.shared.security.NmsUser;
import java.util.List;
import java.util.Map;

/**
 * The estate's read model: what the dashboard's tenant and farm pages show, as nms-core-ui's tenant dashboard and
 * farm card show it. The query side of the tenant and farm contexts — they change the estate, this reads it. Every
 * read is confined to the user's tenants; what lies outside them is not found.
 */
public interface EstateQueries {

  /** The tenants the user may see, with how many farms each has. */
  List<Map<String, Object>> tenants(NmsUser user);

  /** One tenant and every farm of it: connectivity, devices online and offline, open issues by severity. */
  Map<String, Object> tenant(NmsUser user, String tenantId);

  /** One farm, as its farm card shows it: identity, every device, its issues and 30 days of connectivity. */
  Map<String, Object> farm(NmsUser user, String farmId);

  /** One tenant's summary row, as {@link #tenants} lists it. */
  default Map<String, Object> tenantSummary(NmsUser user, String tenantId) {
    return tenants(user).stream().filter(t -> tenantId.equals(t.get("id"))).findFirst()
        .orElseThrow(() -> new NotFoundException("No tenant " + tenantId + "."));
  }

  /** One farm's identity, as {@link #farm} shows it at the top of the page. */
  @SuppressWarnings("unchecked")
  default Map<String, Object> farmSummary(NmsUser user, String farmId) {
    return (Map<String, Object>) farm(user, farmId).get("farm");
  }
}
