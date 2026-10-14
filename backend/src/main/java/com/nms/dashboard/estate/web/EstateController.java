package com.nms.dashboard.estate.web;

import com.nms.dashboard.estate.application.EstateQueries;
import com.nms.dashboard.shared.security.CurrentUser;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The read side of tenants and farms: what the dashboard's tenant and farm pages show. */
@RestController
public class EstateController {

  private final EstateQueries estate;
  private final CurrentUser currentUser;

  public EstateController(EstateQueries estate, CurrentUser currentUser) {
    this.estate = estate;
    this.currentUser = currentUser;
  }

  @GetMapping("/api/tenants")
  public List<Map<String, Object>> tenants() {
    return estate.tenants(currentUser.get());
  }

  /** The tenant page: the tenant and every farm of it. */
  @GetMapping("/api/tenants/{id}")
  public Map<String, Object> tenant(@PathVariable String id) {
    return estate.tenant(currentUser.get(), id);
  }

  /** The farm page: the farm, its devices, its issues and 30 days of connectivity. */
  @GetMapping("/api/farms/{id}")
  public Map<String, Object> farm(@PathVariable String id) {
    return estate.farm(currentUser.get(), id);
  }
}
