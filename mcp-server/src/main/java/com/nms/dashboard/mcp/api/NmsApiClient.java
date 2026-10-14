package com.nms.dashboard.mcp.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nms.dashboard.mcp.security.Caller;
import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The NMS REST API, as the tools call it. Each call carries the caller's own access token, so the API applies the
 * same tenant scope and access checks it applies to the dashboard. Bodies travel as JSON this server does not
 * re-model: tool arguments go out as the model wrote them (the input schemas already checked them), answers come back
 * as {@link JsonNode}.
 */
@Component
public class NmsApiClient {

  private final RestClient http;
  private final ObjectMapper json;
  private final String baseUrl;

  public NmsApiClient(RestClient.Builder builder, ObjectMapper json, @Value("${app.api-url}") String baseUrl) {
    this.http = builder.baseUrl(baseUrl).defaultStatusHandler(HttpStatusCode::isError, this::fail).build();
    this.json = json;
    this.baseUrl = baseUrl;
  }

  /** {@code GET /api/datasets}: datasets, dimensions (with their values), metrics and drill-down paths. */
  public JsonNode datasets(Caller caller) {
    return send(() -> http.get().uri("/api/datasets").headers(h -> authorize(h, caller)).retrieve().body(JsonNode.class));
  }

  /** {@code POST /api/query}: the rows of one aggregate question. */
  public JsonNode query(Caller caller, Map<String, Object> request) {
    return post("/api/query", caller, request);
  }

  /** {@code POST /api/chart}: a chart spec run, with the previous period when it compares. */
  public JsonNode chart(Caller caller, Map<String, Object> spec) {
    return post("/api/chart", caller, spec);
  }

  /** {@code POST /api/auth/logout}: ends the caller's session in Keycloak; the API refuses its tokens from then on. */
  public JsonNode logout(Caller caller) {
    return post("/api/auth/logout", caller, Map.of());
  }

  private JsonNode post(String path, Caller caller, Map<String, Object> body) {
    return send(() -> http.post().uri(path).headers(h -> authorize(h, caller)).contentType(MediaType.APPLICATION_JSON)
        .body(body).retrieve().body(JsonNode.class));
  }

  private static void authorize(HttpHeaders headers, Caller caller) {
    if (caller.token() != null) headers.setBearerAuth(caller.token());
  }

  private JsonNode send(Supplier<JsonNode> call) {
    try {
      return call.get();
    } catch (ResourceAccessException e) {
      throw new NmsApiException("The NMS API is not reachable at " + baseUrl + " — start the backend (npm start).", e);
    }
  }

  /** Every API error is {@code {"error": "..."}}; its status decides how much of it the model sees. */
  private void fail(HttpRequest request, ClientHttpResponse response) throws IOException {
    int status = response.getStatusCode().value();
    String message = errorOf(response);
    throw new NmsApiException(switch (status) {
      case 400, 403, 404, 409 -> message;
      case 401 -> "The NMS API refused the sign-in: " + message;
      default -> "The NMS API failed (" + status + ") on " + request.getURI().getPath() + ": " + message;
    });
  }

  private String errorOf(ClientHttpResponse response) {
    try {
      JsonNode body = json.readTree(response.getBody());
      if (body != null && body.hasNonNull("error")) return body.get("error").asText();
    } catch (IOException | RuntimeException notJson) {
      // fall through to the status text
    }
    try {
      return response.getStatusText();
    } catch (IOException e) {
      return "no details";
    }
  }
}
