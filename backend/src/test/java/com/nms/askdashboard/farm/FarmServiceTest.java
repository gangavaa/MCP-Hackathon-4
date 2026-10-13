package com.nms.askdashboard.farm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nms.askdashboard.api.ApiException;
import com.nms.askdashboard.farm.FarmService.FarmRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class FarmServiceTest {

  private static FarmRequest valid() {
    return new FarmRequest("t-1", " Hoeve Nieuw ", "", "", " Drachten ", "Netherlands", 53.1, 6.09, "7.6.0.1", "IoT", "UNKNOWN", 120);
  }

  @Test
  void aValidFarmIsTrimmed() {
    FarmRequest f = FarmService.validated(valid());
    assertThat(f.name()).isEqualTo("Hoeve Nieuw");
    assertThat(f.address()).isEqualTo("Drachten");
    assertThat(f.version()).isEqualTo("7.6.0.1");
  }

  @Test
  void everyProblemIsNamedAtOnceAsA400() {
    FarmRequest bad = new FarmRequest(" ", "", null, null, "", "", 91.0, -181.0, "seven", "Wifi", "GREEN", -1);
    assertThatThrownBy(() -> FarmService.validated(bad))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST))
        .hasMessageContaining("tenantId is required")
        .hasMessageContaining("name is required")
        .hasMessageContaining("address is required")
        .hasMessageContaining("country is required")
        .hasMessageContaining("latitude must be between -90 and 90")
        .hasMessageContaining("longitude must be between -180 and 180")
        .hasMessageContaining("version must look like 7.6.0.1")
        .hasMessageContaining("protocol must be one of IoT, NoIoT")
        .hasMessageContaining("connectivityStatus must be one of OFFLINE, ONLINE, PARTIALLY_ONLINE, UNKNOWN, WARNING")
        .hasMessageContaining("animals must be between 0 and 1000000");
  }

  @Test
  void missingCoordinatesAreProblemsNotCrashes() {
    FarmRequest v = valid();
    FarmRequest noCoordinates = new FarmRequest(v.tenantId(), v.name(), v.farmName(), v.contractUser(), v.address(), v.country(),
        null, null, v.version(), v.protocol(), v.connectivityStatus(), v.animals());
    assertThatThrownBy(() -> FarmService.validated(noCoordinates)).hasMessageContaining("latitude").hasMessageContaining("longitude");
  }
}
