package com.nms.dashboard.mcp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nms.dashboard.mcp.security.Caller;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class NmsApiClientTest {

  final Caller dairyprod = new Caller("dairyprod", "token-123", "session-1", Instant.now().plusSeconds(300));
  final RestClient.Builder builder = RestClient.builder();
  final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
  final NmsApiClient api = new NmsApiClient(builder, new ObjectMapper(), "http://api.test");

  @Test
  void forwardsTheCallersTokenAndTheArgumentsAsTheyCame() {
    server.expect(requestTo("http://api.test/api/query"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer token-123"))
        .andExpect(content().json("{\"dataset\":\"farms\",\"metric\":\"farm_count\"}"))
        .andRespond(withSuccess("{\"columns\":[],\"rows\":[]}", MediaType.APPLICATION_JSON));

    assertThat(api.query(dairyprod, Map.of("dataset", "farms", "metric", "farm_count")).has("rows")).isTrue();
    server.verify();
  }

  @Test
  void logoutAsksTheApiToEndTheCallersSession() {
    server.expect(requestTo("http://api.test/api/auth/logout"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer token-123"))
        .andRespond(withSuccess("{\"signedOut\":true,\"username\":\"dairyprod\"}", MediaType.APPLICATION_JSON));

    assertThat(api.logout(dairyprod).path("signedOut").asBoolean()).isTrue();
    server.verify();
  }

  @Test
  void sendsNoTokenWhenSignInIsOff() {
    server.expect(requestTo("http://api.test/api/datasets"))
        .andExpect(request -> assertThat(request.getHeaders().containsKey("Authorization")).isFalse())
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    api.datasets(Caller.LOCAL);
    server.verify();
  }

  @Test
  void aBadRequestKeepsTheApisOwnWords() {
    respond(HttpStatus.BAD_REQUEST, "Unknown metric 'count' for farms. Available: farm_count, animals, avg_animals.");
    assertThatThrownBy(() -> api.chart(dairyprod, Map.of()))
        .isInstanceOf(NmsApiException.class)
        .hasMessage("Unknown metric 'count' for farms. Available: farm_count, animals, avg_animals.");
  }

  @Test
  void aForbiddenSaysWhatAccessIsMissing() {
    respond(HttpStatus.FORBIDDEN, "Your account (dairydev) has read-only access.");
    assertThatThrownBy(() -> api.chart(dairyprod, Map.of())).hasMessage("Your account (dairydev) has read-only access.");
  }

  @Test
  void aRefusedTokenIsNamedAsSuch() {
    respond(HttpStatus.UNAUTHORIZED, "The access token was refused: Jwt expired");
    assertThatThrownBy(() -> api.chart(dairyprod, Map.of())).hasMessage("The NMS API refused the sign-in: The access token was refused: Jwt expired");
  }

  @Test
  void aServerErrorNamesTheStatusAndPath() {
    server.expect(requestTo("http://api.test/api/chart")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
    assertThatThrownBy(() -> api.chart(dairyprod, Map.of())).hasMessageStartingWith("The NMS API failed (502) on /api/chart");
  }

  @Test
  void anUnreachableApiSaysWhereItLooked() {
    NmsApiClient offline = new NmsApiClient(RestClient.builder(), new ObjectMapper(), "http://localhost:1");
    assertThatThrownBy(() -> offline.datasets(dairyprod)).hasMessageStartingWith("The NMS API is not reachable at http://localhost:1");
  }

  private void respond(HttpStatus status, String error) {
    server.expect(requestTo("http://api.test/api/chart"))
        .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"" + error + "\"}"));
  }
}
