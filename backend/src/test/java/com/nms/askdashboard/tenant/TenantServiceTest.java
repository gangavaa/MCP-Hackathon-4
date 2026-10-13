package com.nms.askdashboard.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nms.askdashboard.api.ApiException;
import com.nms.askdashboard.tenant.TenantService.TenantRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class TenantServiceTest {

  @Test
  void aValidTenantIsTrimmedAndGetsTheDefaultColour() {
    TenantRequest t = TenantService.validated(new TenantRequest("  Sheep Prod ", "Production", "Dairy", null));
    assertThat(t).isEqualTo(new TenantRequest("Sheep Prod", "Production", "Dairy", TenantService.DEFAULT_COLOR));
  }

  @Test
  void aColourIsKeptInUpperCase() {
    assertThat(TenantService.validated(new TenantRequest("X", "Staging", "Poultry", "#23885c")).color()).isEqualTo("#23885C");
  }

  @Test
  void everyProblemIsNamedAtOnceAsA400() {
    assertThatThrownBy(() -> TenantService.validated(new TenantRequest(" ", "Prod", "Sheep", "green")))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST))
        .hasMessageContaining("name is required")
        .hasMessageContaining("environment must be one of Development, Production, Staging")
        .hasMessageContaining("specie must be one of Dairy, Feedlot, Poultry")
        .hasMessageContaining("color must be a hex colour");
  }

  @Test
  void aNameIsAtMost80Characters() {
    assertThatThrownBy(() -> TenantService.validated(new TenantRequest("x".repeat(81), "Production", "Dairy", null)))
        .hasMessageContaining("at most 80");
  }
}
