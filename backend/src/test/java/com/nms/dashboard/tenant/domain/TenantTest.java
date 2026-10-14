package com.nms.dashboard.tenant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nms.dashboard.shared.error.ValidationException;
import org.junit.jupiter.api.Test;

class TenantTest {

  @Test
  void aNewTenantIsTrimmedAndGetsTheDefaultColour() {
    Tenant t = Tenant.create("t-1", "  Sheep Prod ", "Production", "Dairy", null);
    assertThat(t).isEqualTo(new Tenant("t-1", "Sheep Prod", Environment.PRODUCTION, Specie.DAIRY, Tenant.DEFAULT_COLOR));
  }

  @Test
  void aColourIsKeptInUpperCase() {
    assertThat(Tenant.create("t-1", "X", "Staging", "Poultry", "#23885c").color()).isEqualTo("#23885C");
  }

  @Test
  void everyProblemIsNamedAtOnce() {
    assertThatThrownBy(() -> Tenant.create("t-1", " ", "Prod", "Sheep", "green"))
        .isInstanceOf(ValidationException.class)
        .hasMessageStartingWith("Invalid tenant: ")
        .hasMessageContaining("name is required")
        .hasMessageContaining("environment must be one of Development, Production, Staging")
        .hasMessageContaining("specie must be one of Dairy, Feedlot, Poultry")
        .hasMessageContaining("color must be a hex colour");
  }

  @Test
  void aNameIsAtMost80Characters() {
    assertThatThrownBy(() -> Tenant.create("t-1", "x".repeat(81), "Production", "Dairy", null)).hasMessageContaining("at most 80");
  }

  @Test
  void anEditKeepsWhatItLeavesOut() {
    Tenant before = Tenant.create("t-1", "Sheep Prod", "Production", "Dairy", "#23885C");
    Tenant after = before.edit(null, "Staging", null, null);
    assertThat(after).isEqualTo(new Tenant("t-1", "Sheep Prod", Environment.STAGING, Specie.DAIRY, "#23885C"));
    assertThat(after.classifiesFarmsDifferentlyFrom(before)).isTrue();
    assertThat(before.edit("Renamed", null, null, null).classifiesFarmsDifferentlyFrom(before)).isFalse();
  }
}
