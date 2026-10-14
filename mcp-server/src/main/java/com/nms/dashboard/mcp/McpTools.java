package com.nms.dashboard.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nms.dashboard.mcp.api.ChartData;
import com.nms.dashboard.mcp.api.NmsApiClient;
import com.nms.dashboard.mcp.api.QueryColumn;
import com.nms.dashboard.mcp.api.QueryResult;
import com.nms.dashboard.mcp.security.Caller;
import com.nms.dashboard.mcp.security.SignedOutSessions;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The tools Claude can call. The descriptions are instructions to the model — when to call a tool and how to fill
 * it in — so they are worded for it. {@code show_chart} is the MCP App: its {@code _meta.ui.resourceUri} tells the
 * host to render the panel {@link McpConfig} serves at {@link #PANEL_URI}.
 *
 * <p>Every tool is a call to the NMS REST API ({@link NmsApiClient}) as the caller. What this class adds is only what
 * a model and a chat host need on top: the descriptions and input schemas, the rows as text the model can read, a
 * PNG of the chart, and the panel's payload.
 */
@Component
public class McpTools {

  public static final String PANEL_URI = "ui://nms/dashboard.html";
  /** The older spelling hosts also read; the TypeScript helper registerAppTool sets both. */
  static final String RESOURCE_URI_META_KEY = "ui/resourceUri";

  private static final Logger log = LoggerFactory.getLogger(McpTools.class);
  /** One line per tool call: who asked, which tool, with what. Route the "audit" logger to its own sink in production. */
  private static final Logger audit = LoggerFactory.getLogger("audit");
  private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> MAP = new com.fasterxml.jackson.core.type.TypeReference<>() {};

  private final NmsApiClient api;
  private final ChartImageRenderer images;
  private final ObjectMapper json;
  private final String dashboardUrl;
  private final boolean chartImages;
  private final SignedOutSessions signedOut;
  private final ObjectNode schemas;

  public McpTools(NmsApiClient api, ChartImageRenderer images, ObjectMapper json,
      @Value("${app.dashboard-url}") String dashboardUrl, @Value("${app.chart-images}") boolean chartImages,
      SignedOutSessions signedOut) throws IOException {
    this.api = api;
    this.signedOut = signedOut;
    this.images = images;
    this.json = json;
    this.dashboardUrl = dashboardUrl;
    this.chartImages = chartImages;
    try (InputStream in = getClass().getResourceAsStream("/mcp/schemas.json")) {
      this.schemas = (ObjectNode) json.readTree(in);
    }
  }

  public List<SyncToolSpecification> all() {
    return List.of(
        tool("list_datasets", "List NMS datasets",
            "Lists the NMS estate data that can be charted: datasets (farms, devices, issues, connectivity history), their "
                + "dimensions (with the exact values filters must use) and metrics. Call this first, before query_data or show_chart. "
                + "Everything is limited to the tenants the signed-in user may see.",
            emptySchema(), readOnly(), null,
            (caller, args) -> text(write(api.datasets(caller)))),

        tool("query_data", "Query NMS data",
            "Runs an aggregate query over the NMS estate and returns the rows as data for you to read — no chart. Use it to "
                + "explore, check a hypothesis or find the interesting cut before charting it with show_chart.",
            schemas.get("query").deepCopy(), readOnly(), null,
            (caller, args) -> text(describe(read(api.query(caller, args), QueryResult.class)))),

        tool("show_chart", "Show chart",
            "Renders an interactive chart of NMS data in the conversation. The user can switch chart type and click a category "
                + "to drill down. Use this whenever the user asks to see, show, compare or chart something — charts live in the "
                + "conversation only; the NMS dashboard is a fixed page and nothing is added to it. Look at the data with "
                + "query_data first when you need it to write the insight.",
            chartSpecSchema(), readOnly(), Map.of("ui", Map.of("resourceUri", PANEL_URI), RESOURCE_URI_META_KEY, PANEL_URI),
            this::showChart),

        // Called by the panel only — a drill-down re-runs the chart without a model turn.
        tool("run_chart", "Run chart (panel)",
            "Re-runs a chart spec for the NMS Dashboard chat panel. Used by the panel for drill-downs.",
            chartSpecSchema(), readOnly(), Map.of("ui", Map.of("visibility", List.of("app"))),
            this::runChart),

        tool("logout", "Sign out of NMS",
            "Signs the user out of NMS: ends the sign-in Claude uses to reach NMS, so the next NMS question asks them to sign "
                + "in again — as the same or another user. Call it only when the user asks to sign out, log out or switch user. "
                + "Their other NMS sign-ins (the dashboard in a browser) stay signed in.",
            emptySchema(), ToolAnnotations.builder().readOnlyHint(false).destructiveHint(false).idempotentHint(true).build(), null,
            (caller, args) -> logout(caller)));
  }

  // ------------------------------------------------------------------------------------------- logout

  private CallToolResult logout(Caller caller) {
    api.logout(caller);
    // The API refuses the token now; refusing it here too turns the next /mcp request into the 401 that signs in again.
    if (caller.sessionId() != null) signedOut.add(caller.sessionId(), caller.expiresAt());
    return text("Signed out " + caller.username() + " from NMS. The next NMS question opens the sign-in page — sign in as "
        + "the same or another user.");
  }

  // ------------------------------------------------------------------------------------------- show_chart

  private CallToolResult showChart(Caller caller, Map<String, Object> args) {
    JsonNode chart = api.chart(caller, args);
    ChartData data = read(chart, ChartData.class);
    List<Content> content = new ArrayList<>();
    content.add(new TextContent("Showing \"" + data.spec().title() + "\".\n" + describe(data.result())
        + (data.previous() != null ? "\nPrevious period:\n" + describe(data.previous()) : "")));
    if (chartImages) {
      try {
        content.add(new ImageContent(null, Base64.getEncoder().encodeToString(images.render(data)), "image/png"));
      } catch (RuntimeException e) {
        log.warn("Chart image failed for \"{}\"", data.spec().title(), e);
      }
    }
    String spec = write(chart.get("spec"));
    String interactive = dashboardUrl + "/host?spec=" + Base64.getUrlEncoder().withoutPadding().encodeToString(spec.getBytes(StandardCharsets.UTF_8));
    content.add(new TextContent("Interactive version (drill down; asks the user to sign in) — give the user this link: " + interactive));
    return CallToolResult.builder().content(content).structuredContent(panelPayload(caller, chart, data)).build();
  }

  private CallToolResult runChart(Caller caller, Map<String, Object> args) {
    JsonNode chart = api.chart(caller, args);
    Map<String, Object> payload = panelPayload(caller, chart, read(chart, ChartData.class));
    // The JSON is in the text too: some hosts hand the panel the text but not structuredContent. The model never
    // sees this tool's result, so the size costs nothing.
    return CallToolResult.builder().addTextContent(write(payload)).structuredContent(payload).build();
  }

  /**
   * What the panel renders: the API's chart JSON as it came, where the dashboard is, and which dimension a click
   * drills into — the dataset's {@code drill} from {@code GET /api/datasets}.
   */
  private Map<String, Object> panelPayload(Caller caller, JsonNode chart, ChartData data) {
    Map<String, Object> payload = new LinkedHashMap<>(json.convertValue(chart, MAP));
    payload.put("apiUrl", dashboardUrl);
    payload.put("drill", drill(caller, data.spec().query().dataset()));
    return payload;
  }

  private Map<String, Object> drill(Caller caller, String dataset) {
    for (JsonNode ds : api.datasets(caller)) {
      if (ds.path("id").asText().equals(dataset)) return json.convertValue(ds.path("drill"), MAP);
    }
    return Map.of();
  }

  // ------------------------------------------------------------------------------------------- schemas

  private ObjectNode emptySchema() {
    ObjectNode node = json.createObjectNode().put("type", "object");
    node.putObject("properties");
    return node;
  }

  private ObjectNode chartSpecSchema() {
    ObjectNode spec = schemas.get("chartSpec").deepCopy();
    ((ObjectNode) spec.get("properties")).set("query", schemas.get("query").deepCopy());
    return spec;
  }

  // ------------------------------------------------------------------------------------------- helpers

  private SyncToolSpecification tool(String name, String title, String description, ObjectNode inputSchema,
      ToolAnnotations annotations, Map<String, Object> meta, BiFunction<Caller, Map<String, Object>, CallToolResult> handler) {
    Map<String, Object> schema = json.convertValue(inputSchema, MAP);
    Tool tool = Tool.builder().name(name).title(title).description(description).inputSchema(schema).annotations(annotations).meta(meta).build();
    return SyncToolSpecification.builder()
        .tool(tool)
        .callHandler((context, request) -> {
          Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
          // Put on the transport context by McpConfig, from the verified access token of this very HTTP request.
          if (!(context.get(McpConfig.CALLER_KEY) instanceof Caller caller)) return error("Not signed in.");
          audit.info("user={} tool={} args={}", caller.username(), name, write(args));
          try {
            return handler.apply(caller, args);
          } catch (RuntimeException e) {
            // An NmsApiException carries the API's own words — a 400 lists the valid names, a 403 says what access is
            // missing — returned as tool errors so the model can explain or correct itself.
            return error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
          }
        })
        .build();
  }

  private static ToolAnnotations readOnly() {
    return ToolAnnotations.builder().readOnlyHint(true).build();
  }

  private <T> T read(JsonNode node, Class<T> type) {
    return json.convertValue(node, type);
  }

  private static CallToolResult text(String value) {
    return CallToolResult.builder().addTextContent(value).build();
  }

  private static CallToolResult error(String message) {
    return CallToolResult.builder().addTextContent(message).isError(true).build();
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The rows as a compact table the model can read — capped, so a 400-row trend doesn't flood the context. */
  static String describe(QueryResult result) {
    int max = 40;
    List<String> keys = result.columns().stream().map(QueryColumn::key).toList();
    List<String> lines = new ArrayList<>();
    lines.add(String.join(" | ", keys));
    result.rows().stream().limit(max).forEach(row -> lines.add(keys.stream()
        .map(k -> row.get(k) == null ? "" : String.valueOf(row.get(k))).collect(Collectors.joining(" | "))));
    String more = result.rows().size() > max ? "\n… " + (result.rows().size() - max) + " more rows" : "";
    String window = result.window() != null ? " Window " + result.window().from() + " to " + result.window().to() + "." : "";
    return result.rows().size() + " rows." + window + " Data as of " + result.anchor().substring(0, 10) + ".\n" + String.join("\n", lines) + more;
  }
}
