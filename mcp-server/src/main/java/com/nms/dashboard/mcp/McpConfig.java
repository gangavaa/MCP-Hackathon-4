package com.nms.dashboard.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nms.dashboard.mcp.security.Caller;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.jackson2.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The MCP server, mounted at {@code /mcp} over the SDK's stateless Streamable HTTP transport: no session to keep,
 * because no tool keeps state between calls. Claude Code connects to the URL directly; Claude Desktop launches
 * {@code bridge/McpStdioBridge.java}, which relays stdio to it.
 *
 * <p>Every tool call carries a bearer token (McpSecurityConfig); the caller it names reaches each tool through the
 * transport context, and the tool calls the NMS API with that same token — so it sees only what that user may.
 */
@Configuration
public class McpConfig {

  /** The MCP Apps profile: hosts render a resource with this type as the tool's panel. */
  static final String MCP_APP_MIME_TYPE = "text/html;profile=mcp-app";

  /** Where the caller travels on the MCP transport context, from the HTTP request to the tool handler. */
  static final String CALLER_KEY = "nms.caller";

  @Bean
  HttpServletStatelessServerTransport mcpTransport(ObjectMapper objectMapper, @Value("${app.auth.enabled}") boolean authEnabled) {
    return HttpServletStatelessServerTransport.builder()
        .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
        .messageEndpoint("/mcp")
        // Runs on the request thread, after Spring Security has verified the bearer token: the caller it reads is the
        // one the token names. Tools get them from the context — never from arguments the model could fill in.
        .contextExtractor(request -> {
          Caller caller = authEnabled ? Caller.current() : Caller.LOCAL;
          return caller == null ? McpTransportContext.EMPTY : McpTransportContext.create(Map.of(CALLER_KEY, caller));
        })
        .build();
  }

  @Bean
  ServletRegistrationBean<HttpServletStatelessServerTransport> mcpServlet(HttpServletStatelessServerTransport transport) {
    return new ServletRegistrationBean<>(transport, "/mcp");
  }

  @Bean(destroyMethod = "close")
  McpStatelessSyncServer mcpServer(HttpServletStatelessServerTransport transport, ObjectMapper objectMapper, McpTools tools,
      McpPanel panelFile) {
    Resource panel = Resource.builder()
        .uri(McpTools.PANEL_URI)
        .name("NMS Dashboard")
        .description("Interactive NMS chart: switch chart type, drill down, pin to the live dashboard.")
        .mimeType(MCP_APP_MIME_TYPE)
        .build();
    SyncResourceSpecification panelSpec = new SyncResourceSpecification(panel, (context, request) -> {
      String html = panelFile.html().orElseThrow(() -> new IllegalStateException(McpPanel.NOT_BUILT));
      return new ReadResourceResult(List.of(new TextResourceContents(McpTools.PANEL_URI, MCP_APP_MIME_TYPE, html)));
    });

    return McpServer.sync(transport)
        .serverInfo("nms-dashboard", "0.2.0")
        .instructions("NMS estate data (farms, devices, issues, connectivity). Call list_datasets first; chart with show_chart.")
        .capabilities(ServerCapabilities.builder().tools(false).resources(false, false).build())
        .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
        .jsonSchemaValidator(new DefaultJsonSchemaValidator(objectMapper))
        .tools(tools.all())
        .resources(panelSpec)
        .build();
  }
}
