package com.nms.dashboard.farm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nms.dashboard.shared.error.ValidationException;
import com.nms.dashboard.tenant.domain.Environment;
import com.nms.dashboard.tenant.domain.Specie;
import com.nms.dashboard.tenant.domain.Tenant;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FarmTest {

  private static final Tenant DAIRY = new Tenant("t-1", "Dairy Prod", Environment.PRODUCTION, Specie.DAIRY, "#23885C");
  private static final Tenant FEEDLOT = new Tenant("t-2", "Feedlot Staging", Environment.STAGING, Specie.FEEDLOT, "#7048CC");
  private static final Instant REGISTERED = Instant.parse("2026-10-01T00:00:00Z");
  private static final Instant LATER = Instant.parse("2026-10-12T00:00:00Z");

  private static FarmDetails details(String tenantId) {
    return new FarmDetails(tenantId, " Hoeve Nieuw ", null, null, " Drachten ", "Netherlands", 53.1, 6.09, "7.6.0.1", null, null, null);
  }

  private static Farm registered() {
    return Farm.register("f-1", "tpl", details("t-1"), DAIRY, REGISTERED);
  }

  @Test
  void aNewFarmIsTrimmedDefaultedAndTakesItsTenantsSpecieAndEnvironment() {
    Farm f = registered();
    assertThat(f.name()).isEqualTo("Hoeve Nieuw");
    assertThat(f.location().town()).isEqualTo("Drachten");
    assertThat(f.protocol()).isEqualTo(Protocol.IOT);
    assertThat(f.connectivity()).isEqualTo(ConnectivityStatus.UNKNOWN);
    assertThat(f.animals()).isZero();
    assertThat(f.specie()).isEqualTo(Specie.DAIRY);
    assertThat(f.environment()).isEqualTo(Environment.PRODUCTION);
    assertThat(f.registeredAt()).isEqualTo(REGISTERED);
    assertThat(f.lastUpgradeAt()).isNull();
  }

  @Test
  void everyProblemIsNamedAtOnce() {
    FarmDetails bad = new FarmDetails(" ", "", null, null, "", "", 91.0, -181.0, "seven", "Wifi", "GREEN", -1);
    assertThatThrownBy(() -> Farm.register("f-1", "tpl", bad, null, REGISTERED))
        .isInstanceOf(ValidationException.class)
        .hasMessageStartingWith("Invalid farm: ")
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
  void anUnknownTenantIsAProblem() {
    assertThatThrownBy(() -> Farm.register("f-1", "tpl", details("nope"), null, REGISTERED)).hasMessageContaining("no tenant nope");
  }

  @Test
  void aNewVersionIsAnUpgradeAndOtherEditsAreNot() {
    Farm f = registered();
    Farm renamed = f.edit(new FarmDetails(null, "Hoeve Oud", null, null, null, null, null, null, null, null, null, 300), DAIRY, LATER);
    assertThat(renamed.name()).isEqualTo("Hoeve Oud");
    assertThat(renamed.animals()).isEqualTo(300);
    assertThat(renamed.location()).isEqualTo(f.location());
    assertThat(renamed.lastUpgradeAt()).isNull();
    Farm upgraded = f.edit(new FarmDetails(null, null, null, null, null, null, null, null, "7.7.0.0", null, null, null), DAIRY, LATER);
    assertThat(upgraded.lastUpgradeAt()).isEqualTo(LATER);
  }

  @Test
  void movingToAnotherTenantTakesThatTenantsSpecieAndEnvironment() {
    Farm f = registered();
    Farm moved = f.edit(new FarmDetails("t-2", null, null, null, null, null, null, null, null, null, null, null), FEEDLOT, LATER);
    assertThat(moved.tenantId()).isEqualTo("t-2");
    assertThat(moved.specie()).isEqualTo(Specie.FEEDLOT);
    assertThat(moved.environment()).isEqualTo(Environment.STAGING);
    assertThat(moved.movedFrom(f)).isTrue();
    assertThat(moved.registeredAt()).isEqualTo(REGISTERED);
  }
}
