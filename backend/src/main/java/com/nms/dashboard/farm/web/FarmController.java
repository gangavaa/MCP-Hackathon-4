package com.nms.dashboard.farm.web;

import com.nms.dashboard.estate.application.EstateQueries;
import com.nms.dashboard.farm.application.FarmAdministration;
import com.nms.dashboard.farm.domain.Farm;
import com.nms.dashboard.farm.domain.FarmDetails;
import com.nms.dashboard.shared.security.CurrentUser;
import com.nms.dashboard.shared.security.NmsUser;
import java.net.URI;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Farm administration — admins only. Answers with the farm as its page shows it (the estate's read model). */
@RestController
public class FarmController {

  /**
   * The JSON body of a create (tenantId, name, address, country, latitude, longitude, version; the rest optional)
   * or an edit (any field; a new tenantId moves the farm). {@code address} is the farm's town.
   */
  public record FarmRequest(String tenantId, String name, String farmName, String contractUser, String address,
      String country, Double latitude, Double longitude, String version, String protocol, String connectivityStatus,
      Integer animals) {

    FarmDetails toDetails() {
      return new FarmDetails(tenantId, name, farmName, contractUser, address, country, latitude, longitude, version, protocol,
          connectivityStatus, animals);
    }
  }

  private final FarmAdministration administration;
  private final EstateQueries estate;
  private final CurrentUser currentUser;

  public FarmController(FarmAdministration administration, EstateQueries estate, CurrentUser currentUser) {
    this.administration = administration;
    this.estate = estate;
    this.currentUser = currentUser;
  }

  @PostMapping("/api/farms")
  public ResponseEntity<Map<String, Object>> register(@RequestBody(required = false) FarmRequest request) {
    NmsUser user = currentUser.get();
    Farm farm = administration.register(user, request == null ? null : request.toDetails());
    return ResponseEntity.created(URI.create("/api/farms/" + farm.id())).body(estate.farmSummary(user, farm.id()));
  }

  /** What is left out stays as it is. */
  @PatchMapping("/api/farms/{id}")
  public Map<String, Object> edit(@PathVariable String id, @RequestBody(required = false) FarmRequest changes) {
    NmsUser user = currentUser.get();
    administration.edit(user, id, changes == null ? null : changes.toDetails());
    return estate.farmSummary(user, id);
  }

  /** A farm with devices, issues or history is refused (409) unless {@code ?cascade=true}. */
  @DeleteMapping("/api/farms/{id}")
  public ResponseEntity<Void> delete(@PathVariable String id, @RequestParam(defaultValue = "false") boolean cascade) {
    administration.delete(currentUser.get(), id, cascade);
    return ResponseEntity.noContent().build();
  }
}
