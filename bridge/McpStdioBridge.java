import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Claude Desktop launches local MCP servers as processes speaking JSON-RPC over stdio. Starting the whole backend per
 * session would mean one Postgres and one port per chat, so Desktop launches this instead: a relay from stdio to the
 * one running backend's {@code /mcp} endpoint (Streamable HTTP, stateless).
 *
 * <p><b>Sign-in.</b> The backend answers 401 with {@code WWW-Authenticate: Bearer resource_metadata="…"}. The bridge
 * follows it — protected resource metadata, then the authorization server's discovery document — and signs the user
 * in the way desktop apps do (RFC 8252): it opens the browser on the sign-in page, receives the code on a one-off
 * loopback port (http://127.0.0.1:PORT/callback), exchanges it with PKCE, and keeps the refresh token in
 * {@code ~/.nms-ask-dashboard/bridge-token} (readable by this user only), so later sessions start signed in.
 * {@code NMS_LOGIN=device} uses the device flow instead (sign-in page with a code, for machines without a local
 * browser); {@code $NMS_TOKEN} skips all of it with a fixed access token, for scripts and tests.
 *
 * <p>Run as a single-file program — no build, no dependencies: {@code java bridge/McpStdioBridge.java [url]}.
 * The URL defaults to {@code $NMS_MCP_URL}, then {@code http://localhost:4300/mcp}. Logs go to stderr; stdout is the
 * MCP channel.
 */
public final class McpStdioBridge {

  private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|-?\\d+)");
  private static final PrintStream OUT = new PrintStream(System.out, true, StandardCharsets.UTF_8);
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private static String url;
  private static final Auth auth = new Auth();

  public static void main(String[] args) throws Exception {
    boolean loginOnly = args.length > 0 && args[0].equals("--login");
    String[] rest = loginOnly ? java.util.Arrays.copyOfRange(args, 1, args.length) : args;
    url = rest.length > 0 ? rest[0] : System.getenv().getOrDefault("NMS_MCP_URL", "http://localhost:4300/mcp");
    if (loginOnly) {
      login();
      return;
    }
    // Calls run concurrently, so a long create_dashboard does not hold up a ping.
    ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    log("stdio <-> " + url);

    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    for (String line; (line = in.readLine()) != null; ) {
      String message = line.trim();
      if (message.isEmpty()) continue;
      pool.submit(() -> relay(message));
    }
    pool.shutdown();
  }

  /**
   * {@code --login}: sign in now and keep the token, so the first question in Claude Desktop does not wait on a
   * browser. Ask the backend once without a token; its 401 says where to sign in.
   */
  private static void login() throws Exception {
    String probe = "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"ping\"}";
    HttpResponse<String> first = post(probe, auth.token());
    if (first.statusCode() != 401) {
      System.out.println("Already signed in to NMS.");
      return;
    }
    auth.signIn(first.headers().firstValue("WWW-Authenticate").orElse(""));
    System.out.println(post(probe, auth.token()).statusCode() == 401 ? "Sign-in did not take — try again." : "Signed in to NMS. Claude Desktop will use this sign-in.");
  }

  private static void relay(String message) {
    try {
      HttpResponse<String> response = post(message, auth.token());
      if (response.statusCode() == 401) {
        // No token yet, or it expired or was revoked: sign in (or refresh), then try once more.
        auth.signIn(response.headers().firstValue("WWW-Authenticate").orElse(""));
        response = post(message, auth.token());
      }
      String body = response.body() == null ? "" : response.body().trim();
      if (body.isEmpty()) {
        if (response.statusCode() >= 400) reply(error(message, "NMS backend answered HTTP " + response.statusCode()));
        return; // a notification: nothing to send back
      }
      if (response.statusCode() == 401 || response.statusCode() == 403) {
        reply(error(message, "Not signed in to NMS: " + field(body, "error")));
        return;
      }
      String type = response.headers().firstValue("Content-Type").orElse("");
      if (type.startsWith("text/event-stream")) {
        for (String event : body.split("\n")) {
          if (event.startsWith("data:")) reply(event.substring(5).trim());
        }
      } else {
        reply(body.replace("\n", ""));
      }
    } catch (java.net.ConnectException e) {
      reply(error(message, "The NMS backend is not running at " + url + " — start it with `npm start` in nms-ask-dashboard."));
    } catch (Exception e) {
      log(e.toString());
      reply(error(message, "Bridge error: " + e.getMessage()));
    }
  }

  private static HttpResponse<String> post(String message, String token) throws IOException, InterruptedException {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofMinutes(2))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .POST(HttpRequest.BodyPublishers.ofString(message, StandardCharsets.UTF_8));
    if (token != null) request.header("Authorization", "Bearer " + token);
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  // ------------------------------------------------------------------------------------------- sign-in

  /** Tokens for the backend, from the authorization server it names. One sign-in at a time, shared by all calls. */
  static final class Auth {

    private static final Path STORE = Path.of(System.getProperty("user.home"), ".nms-ask-dashboard", "bridge-token");
    private final String clientId = System.getenv().getOrDefault("NMS_CLIENT_ID", "nms-bridge");
    private final String fixed = blankToNull(System.getenv("NMS_TOKEN"));
    private String accessToken;
    private Instant expiresAt = Instant.EPOCH;
    private String refreshToken;
    private String tokenEndpoint;
    private String deviceEndpoint;
    private String authorizationEndpoint;

    Auth() {
      try {
        if (Files.exists(STORE)) {
          String saved = Files.readString(STORE);
          refreshToken = field(saved, "refresh_token");
          tokenEndpoint = field(saved, "token_endpoint");
          deviceEndpoint = field(saved, "device_endpoint");
        }
      } catch (IOException e) {
        log("could not read " + STORE + ": " + e.getMessage());
      }
    }

    synchronized String token() throws IOException, InterruptedException {
      if (fixed != null) return fixed;
      if (accessToken != null && Instant.now().isBefore(expiresAt.minusSeconds(30))) return accessToken;
      if (refreshToken != null && tokenEndpoint != null && refresh()) return accessToken;
      return null; // the backend's 401 starts the sign-in
    }

    synchronized void signIn(String wwwAuthenticate) throws IOException, InterruptedException {
      if (fixed != null) return; // a fixed token that was refused: nothing to do but report it
      if (accessToken != null && Instant.now().isBefore(expiresAt.minusSeconds(30))) {
        accessToken = null; // refused while still valid: revoked — sign in again
      } else if (refreshToken != null && tokenEndpoint != null && refresh()) {
        return;
      }
      discover(wwwAuthenticate);
      if ("device".equals(System.getenv("NMS_LOGIN")) || authorizationEndpoint == null) deviceFlow();
      else browserFlow();
    }

    /** RFC 8252: the sign-in page in the browser, the code back on a loopback port, exchanged with PKCE (RFC 7636). */
    private void browserFlow() throws IOException, InterruptedException {
      String verifier = randomToken(48);
      String state = randomToken(16);
      CompletableFuture<String> code = new CompletableFuture<>();
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      String redirect = "http://127.0.0.1:" + server.getAddress().getPort() + "/callback";
      server.createContext("/callback", exchange -> {
        Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
        boolean ok = state.equals(query.get("state")) && query.get("code") != null;
        if (ok) {
          page(exchange, 200, "Signed in to NMS", "You can close this tab and go back to Claude — your question is being answered.");
        } else {
          page(exchange, 400, "Sign-in did not finish",
              "Ask Claude again to retry. (" + query.getOrDefault("error_description", query.getOrDefault("error", "no code")) + ")");
        }
        if (ok) code.complete(query.get("code"));
        else code.completeExceptionally(new IOException("Sign-in failed: " + query.getOrDefault("error", "no code")));
      });
      server.start();
      try {
        String page = authorizationEndpoint + "?" + encode(Map.of(
            "response_type", "code", "client_id", clientId, "redirect_uri", redirect, "scope", "openid offline_access",
            "state", state, "code_challenge", base64url(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))),
            "code_challenge_method", "S256"));
        log("sign in to NMS: " + page);
        openBrowser(page);
        String received = code.get(5, TimeUnit.MINUTES);
        take(form(tokenEndpoint, Map.of("grant_type", "authorization_code", "code", received, "redirect_uri", redirect,
            "client_id", clientId, "code_verifier", verifier)));
        if (accessToken == null) throw new IOException("Sign-in failed: the authorization server issued no token.");
        log("signed in");
      } catch (TimeoutException e) {
        throw new IOException("Sign-in timed out — ask Claude again to retry.");
      } catch (java.util.concurrent.ExecutionException e) {
        throw new IOException(e.getCause().getMessage());
      } catch (java.security.NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      } finally {
        server.stop(1);
      }
    }

    private static void page(com.sun.net.httpserver.HttpExchange exchange, int status, String title, String text) throws IOException {
      byte[] html = ("<!doctype html><meta charset=utf-8><title>" + title + "</title><body style=\"font-family:system-ui;"
          + "display:grid;place-items:center;height:90vh;color:#1f2937\"><div style=\"text-align:center\"><div style=\"display:inline-block;"
          + "background:#004a75;color:#fff;font-weight:700;padding:2px 8px;border-radius:5px\">NMS</div><h1>" + title + "</h1><p>" + text
          + "</p></div>").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
      exchange.sendResponseHeaders(status, html.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(html);
      }
    }

    /** 401 → protected resource metadata (RFC 9728) → authorization server metadata (OIDC discovery). */
    private void discover(String wwwAuthenticate) throws IOException, InterruptedException {
      Matcher m = Pattern.compile("resource_metadata=\"([^\"]+)\"").matcher(wwwAuthenticate);
      if (!m.find()) throw new IOException("The backend asked for a token but did not say where to get one.");
      String issuer = firstArrayItem(get(m.group(1)), "authorization_servers");
      String config = get(issuer + "/.well-known/openid-configuration");
      tokenEndpoint = field(config, "token_endpoint");
      deviceEndpoint = field(config, "device_authorization_endpoint");
      authorizationEndpoint = field(config, "authorization_endpoint");
    }

    /** RFC 8628: show the user a sign-in page, poll until they have signed in. */
    private void deviceFlow() throws IOException, InterruptedException {
      String start = form(deviceEndpoint, Map.of("client_id", clientId, "scope", "openid offline_access"));
      String deviceCode = field(start, "device_code");
      if (deviceCode == null) throw new IOException("Sign-in could not start: " + start);
      String page = field(start, "verification_uri_complete");
      log("sign in to NMS: " + page + "  (code " + field(start, "user_code") + ")");
      openBrowser(page);
      long interval = Long.parseLong(fieldOr(start, "interval", "5"));
      Instant deadline = Instant.now().plusSeconds(Long.parseLong(fieldOr(start, "expires_in", "600")));
      while (Instant.now().isBefore(deadline)) {
        Thread.sleep(interval * 1000);
        String answer = form(tokenEndpoint, Map.of(
            "grant_type", "urn:ietf:params:oauth:grant-type:device_code", "client_id", clientId, "device_code", deviceCode));
        String error = field(answer, "error");
        if (error == null) {
          take(answer);
          log("signed in");
          return;
        }
        if (error.equals("slow_down")) interval += 5;
        else if (!error.equals("authorization_pending")) throw new IOException("Sign-in failed: " + error);
      }
      throw new IOException("Sign-in timed out — ask again to retry.");
    }

    private boolean refresh() throws IOException, InterruptedException {
      String answer = form(tokenEndpoint, Map.of("grant_type", "refresh_token", "client_id", clientId, "refresh_token", refreshToken));
      if (field(answer, "access_token") == null) {
        refreshToken = null; // expired or revoked: the next 401 signs in again
        return false;
      }
      take(answer);
      return true;
    }

    private void take(String tokenResponse) throws IOException {
      accessToken = field(tokenResponse, "access_token");
      expiresAt = Instant.now().plusSeconds(Long.parseLong(fieldOr(tokenResponse, "expires_in", "300")));
      String refresh = field(tokenResponse, "refresh_token");
      if (refresh != null) refreshToken = refresh;
      Files.createDirectories(STORE.getParent());
      if (accessToken == null) return;
      Files.writeString(STORE, "{\"refresh_token\":\"" + refreshToken + "\",\"token_endpoint\":\"" + tokenEndpoint
          + "\",\"device_endpoint\":\"" + deviceEndpoint + "\"}");
      try {
        Files.setPosixFilePermissions(STORE, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException windows) {
        // the user's profile directory is private there already
      }
    }

    private static void openBrowser(String page) {
      if (System.getenv("NMS_NO_BROWSER") != null) return; // tests sign in on the page themselves
      String os = System.getProperty("os.name").toLowerCase();
      String[] command = os.contains("mac") ? new String[] {"open", page}
          : os.contains("win") ? new String[] {"rundll32", "url.dll,FileProtocolHandler", page}
          : new String[] {"xdg-open", page};
      try {
        new ProcessBuilder(command).inheritIO().start();
      } catch (IOException e) {
        log("open this page to sign in: " + page);
      }
    }
  }

  // ------------------------------------------------------------------------------------------- helpers

  private static String get(String target) throws IOException, InterruptedException {
    return HTTP.send(HttpRequest.newBuilder(URI.create(target)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
  }

  private static String form(String target, Map<String, String> fields) throws IOException, InterruptedException {
    String body = encode(fields);
    HttpRequest request = HttpRequest.newBuilder(URI.create(target))
        .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body();
  }

  private static String encode(Map<String, String> fields) {
    return fields.entrySet().stream()
        .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
        .collect(Collectors.joining("&"));
  }

  private static Map<String, String> query(String raw) {
    Map<String, String> out = new HashMap<>();
    if (raw == null) return out;
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0) out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
    }
    return out;
  }

  private static String randomToken(int bytes) {
    byte[] b = new byte[bytes];
    new SecureRandom().nextBytes(b);
    return base64url(b);
  }

  private static String base64url(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** A top-level string or number field of a flat JSON object — all the token endpoints return. */
  static String field(String json, String name) {
    Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|(-?\\d+))").matcher(json == null ? "" : json);
    if (!m.find()) return null;
    return m.group(1) != null ? m.group(1).replace("\\/", "/") : m.group(2);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String fieldOr(String json, String name, String fallback) {
    String value = field(json, name);
    return value != null ? value : fallback;
  }

  private static String firstArrayItem(String json, String name) throws IOException {
    Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\\[\\s*\"([^\"]+)\"").matcher(json);
    if (!m.find()) throw new IOException("No " + name + " in " + json);
    return m.group(1);
  }

  /** A JSON-RPC error for a request; null for a notification, which takes no reply. */
  private static String error(String request, String text) {
    Matcher id = ID.matcher(request);
    if (!id.find()) return null;
    String escaped = text.replace("\\", "\\\\").replace("\"", "\\\"");
    return "{\"jsonrpc\":\"2.0\",\"id\":" + id.group(1) + ",\"error\":{\"code\":-32000,\"message\":\"" + escaped + "\"}}";
  }

  private static synchronized void reply(String json) {
    if (json != null && !json.isEmpty()) OUT.println(json);
  }

  private static void log(String text) {
    System.err.println("nms-ask-dashboard bridge: " + text);
  }
}
