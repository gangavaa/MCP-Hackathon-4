package com.nms.askdashboard.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nms.askdashboard.model.ChartData;
import com.nms.askdashboard.model.ChartSpec;
import com.nms.askdashboard.model.QueryColumn;
import com.nms.askdashboard.model.QueryRequest;
import com.nms.askdashboard.model.QueryResult;
import com.nms.askdashboard.query.QueryService;
import com.nms.askdashboard.security.NmsUser;
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
 */
@Component
public class McpTools {

  public static final String PANEL_URI = "ui://nms/ask-dashboard.html";
  /** The older spelling hosts also read; the TypeScript helper registerAppTool sets both. */
  static final String RESOURCE_URI_META_KEY = "ui/resourceUri";

  private static final Logger log = LoggerFactory.getLogger(McpTools.class);
  /** One line per tool call: who asked, which tool, with what. Route the "audit" logger to its own sink in production. */
  private static final Logger audit = LoggerFactory.getLogger("audit");
  private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> MAP = new com.fasterxml.jackson.core.type.TypeReference<>() {};

  private final QueryService queries;
  private final ChartImageRenderer images;
  private final ObjectMapper json;
  private final String publicUrl;
  private final boolean chartImages;
  private final ObjectNode schemas;

  public McpTools(QueryService queries, ChartImageRenderer images, ObjectMapper json,
      @Value("${app.public-url}") String publicUrl, @Value("${app.chart-images}") boolean chartImages) throws IOException {
    this.queries = queries;
    this.images = images;
    this.json = json;
    this.publicUrl = publicUrl;
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
            (user, args) -> text(write(queries.datasets(user)))),

        tool("query_data", "Query NMS data",
            "Runs an aggregate query over the NMS estate and returns the rows as data for you to read — no chart. Use it to "
                + "explore, check a hypothesis or find the interesting cut before charting it with show_chart.",
            schemas.get("query").deepCopy(), readOnly(), null,
            (user, args) -> text(describe(queries.run(json.convertValue(args, QueryRequest.class), user)))),

        tool("show_chart", "Show chart",
            "Renders an interactive chart of NMS data in the conversation. The user can switch chart type and click a category "
                + "to drill down. Use this whenever the user asks to see, show, compare or chart something — charts live in the "
                + "conversation only; the NMS dashboard is a fixed page and nothing is added to it. Look at the data with "
                + "query_data first when you need it to write the insight.",
            chartSpecSchema(), readOnly(), Map.of("ui", Map.of("resourceUri", PANEL_URI), RESOURCE_URI_META_KEY, PANEL_URI),
            (user, args) -> showChart(user, args)),

        // Called by the panel only — a drill-down re-runs the chart without a model turn.
        tool("run_chart", "Run chart (panel)",
            "Re-runs a chart spec for the Ask-the-Dashboard panel. Used by the panel for drill-downs.",
            chartSpecSchema(), readOnly(), Map.of("ui", Map.of("visibility", List.of("app"))),
            this::runChart));
  }

  // ------------------------------------------------------------------------------------------- show_chart

  private CallToolResult showChart(NmsUser user, Map<String, Object> args) {
    ChartSpec spec = spec(args);
    ChartData data = queries.chart(spec, user);
    List<Content> content = new ArrayList<>();
    content.add(new TextContent("Showing \"" + spec.title() + "\".\n" + describe(data.result())
        + (data.previous() != null ? "\nPrevious period:\n" + describe(data.previous()) : "")));
    if (chartImages) {
      try {
        content.add(new ImageContent(null, Base64.getEncoder().encodeToString(images.render(data)), "image/png"));
      } catch (RuntimeException e) {
        log.warn("Chart image failed for \"{}\"", spec.title(), e);
      }
    }
    String interactive = publicUrl + "/host?spec=" + Base64.getUrlEncoder().withoutPadding().encodeToString(write(spec).getBytes(StandardCharsets.UTF_8));
    content.add(new TextContent("Interactive version (drill down; asks the user to sign in) — give the user this link: " + interactive));
    return CallToolResult.builder().content(content).structuredContent(panelPayload(data)).build();
  }

  private CallToolResult runChart(NmsUser user, Map<String, Object> args) {
    Map<String, Object> payload = panelPayload(queries.chart(spec(args), user));
    // The JSON is in the text too: some hosts hand the panel the text but not structuredContent. The model never
    // sees this tool's result, so the size costs nothing.
    return CallToolResult.builder().addTextContent(write(payload)).structuredContent(payload).build();
  }

  /** What the panel renders: the chart data, where the API is, and which dimension a click drills into. */
  private Map<String, Object> panelPayload(ChartData data) {
    Map<String, Object> payload = new LinkedHashMap<>(json.convertValue(data, MAP));
    payload.put("apiUrl", publicUrl);
    payload.put("drill", queries.drill(data.spec().query().dataset()));
    return payload;
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
      ToolAnnotations annotations, Map<String, Object> meta, BiFunction<NmsUser, Map<String, Object>, CallToolResult> handler) {
    Map<String, Object> schema = json.convertValue(inputSchema, MAP);
    Tool tool = Tool.builder().name(name).title(title).description(description).inputSchema(schema).annotations(annotations).meta(meta).build();
    return SyncToolSpecification.builder()
        .tool(tool)
        .callHandler((context, request) -> {
          Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
          // Put on the transport context by McpConfig, from the verified access token of this very HTTP request.
          if (!(context.get(McpConfig.USER_KEY) instanceof NmsUser user)) return error("Not signed in.");
          audit.info("user={} tool={} args={}", user.username(), name, write(args));
          try {
            return handler.apply(user, args);
          } catch (RuntimeException e) {
            // A QueryException's message lists the valid names; a ForbiddenException says what access is missing —
            // both returned as tool errors so the model can explain or correct itself.
            return error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
          }
        })
        .build();
  }

  private static ToolAnnotations readOnly() {
    return ToolAnnotations.builder().readOnlyHint(true).build();
  }

  private ChartSpec spec(Map<String, ?> args) {
    return json.convertValue(args, ChartSpec.class);
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
