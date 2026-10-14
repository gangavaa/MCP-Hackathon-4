package com.nms.dashboard.tenant.web;

import com.nms.dashboard.estate.application.EstateQueries;
import com.nms.dashboard.shared.security.CurrentUser;
import com.nms.dashboard.shared.security.NmsUser;
import com.nms.dashboard.tenant.application.TenantAdministration;
import com.nms.dashboard.tenant.application.TenantAdministration.TenantDetails;
import com.nms.dashboard.tenant.domain.Tenant;
import java.net.URI;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant administration — admins only. Answers with the tenant as {@code GET /api/tenants} lists it (the estate's
 * read model), farm count included.
 */
@RestController
public class TenantController {

  /** The JSON body of a create (name, environment, specie, optional color) or an edit (any of them). */
  public record TenantRequest(String name, String environment, String specie, String color) {

    TenantDetails toDetails() {
      return new TenantDetails(name, environment, specie, color);
    }
  }

  private final TenantAdministration administration;
  private final EstateQueries estate;
  private final CurrentUser currentUser;

  public TenantController(TenantAdministration administration, EstateQueries estate, CurrentUser currentUser) {
    this.administration = administration;
    this.estate = estate;
    this.currentUser = currentUser;
  }

  @PostMapping("/api/tenants")
  public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) TenantRequest request) {
    NmsUser user = currentUser.get();
    Tenant tenant = administration.create(user, request == null ? null : request.toDetails());
    return ResponseEntity.created(URI.create("/api/tenants/" + tenant.id())).body(estate.tenantSummary(user, tenant.id()));
  }

  /** What is left out stays as it is. */
  @PatchMapping("/api/tenants/{id}")
  public Map<String, Object> edit(@PathVariable String id, @RequestBody(required = false) TenantRequest changes) {
    NmsUser user = currentUser.get();
    administration.edit(user, id, changes == null ? null : changes.toDetails());
    return estate.tenantSummary(user, id);
  }

  /** Refused (409) while the tenant still has farms. */
  @DeleteMapping("/api/tenants/{id}")
  public ResponseEntity<Void> delete(@PathVariable String id) {
    administration.delete(currentUser.get(), id);
    return ResponseEntity.noContent().build();
  }
}
