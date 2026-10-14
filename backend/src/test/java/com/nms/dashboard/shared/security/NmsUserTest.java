package com.nms.dashboard.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nms.dashboard.shared.error.ForbiddenException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class NmsUserTest {

  static Jwt token(Object tenants, String access) {
    Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").subject("sub-1").claim("preferred_username", "dairy")
        .claim("name", "Dana Dairy").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
    if (tenants != null) b.claim("tenants", tenants);
    if (access != null) b.claim("nms_access", access);
    return b.build();
  }

  @Test
  void readsTenantsAndAccessFromTheToken() {
    NmsUser user = NmsUser.fromJwt(token(List.of("a", "b"), "write"));
    assertThat(user.tenants()).isEqualTo(TenantScope.of(Set.of("a", "b")));
    assertThat(user.canWrite()).isTrue();
    assertThat(user.owner()).isEqualTo("sub-1");
  }

  @Test
  void aStarMeansEveryTenant() {
    assertThat(NmsUser.fromJwt(token(List.of("*"), "read")).tenants().all()).isTrue();
  }

  @Test
  void onlyEveryTenantWithWriteAccessIsAnAdmin() {
    assertThat(NmsUser.fromJwt(token(List.of("*"), "write")).isAdmin()).isTrue();
    assertThat(NmsUser.fromJwt(token(List.of("*"), "read")).isAdmin()).isFalse();
    NmsUser tenantUser = NmsUser.fromJwt(token(List.of("a"), "write"));
    assertThat(tenantUser.isAdmin()).isFalse();
    assertThatThrownBy(tenantUser::requireAdmin).isInstanceOf(ForbiddenException.class).hasMessageContaining("Only an admin");
  }

  @Test
  void aTokenWithoutClaimsGetsNothingAndReadOnly() {
    NmsUser user = NmsUser.fromJwt(token(null, null));
    assertThat(user.tenants()).isEqualTo(TenantScope.NONE);
    assertThat(user.canWrite()).isFalse();
    assertThatThrownBy(user::requireWrite).isInstanceOf(ForbiddenException.class).hasMessageContaining("read-only");
  }
}
