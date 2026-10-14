package com.nms.dashboard.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SignedOutSessionsTest {

  final SignedOutSessions sessions = new SignedOutSessions();

  @Test
  void aSignedOutSessionIsRefusedUntilItsLastTokenExpires() {
    sessions.add("s1", Instant.now().plusSeconds(60));
    assertThat(sessions.contains("s1")).isTrue();
    assertThat(sessions.contains("s2")).isFalse();
  }

  @Test
  void anEntryPastItsTokensExpiryNoLongerCounts() {
    sessions.add("s1", Instant.now().minusSeconds(1));
    assertThat(sessions.contains("s1")).isFalse();
  }

  @Test
  void aTokenWithoutASessionIsNeverSignedOut() {
    assertThat(sessions.contains(null)).isFalse();
  }
}
