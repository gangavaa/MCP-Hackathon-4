package com.nms.askdashboard.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nms.askdashboard.security.CurrentUser;
import com.nms.askdashboard.security.ForbiddenException;
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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <p>Every request carries a bearer token (SecurityConfig); the user it names reaches each tool through the
 * transport context, so a tool runs as — and sees only what — that user may.
 */
@Configuration
public class McpConfig {

  /** The MCP Apps profile: hosts render a resource with this type as the tool's panel. */
  static final String MCP_APP_MIME_TYPE = "text/html;profile=mcp-app";

  /** Where the signed-in user travels on the MCP transport context, from the HTTP request to the tool handler. */
  static final String USER_KEY = "nms.user";

  @Bean
  HttpServletStatelessServerTransport mcpTransport(ObjectMapper objectMapper, CurrentUser currentUser) {
    return HttpServletStatelessServerTransport.builder()
        .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
        .messageEndpoint("/mcp")
        // Runs on the request thread, after Spring Security has verified the bearer token: the user it reads is the
        // one the token names. Tools get them from the context — never from arguments the model could fill in.
        .contextExtractor(request -> {
          try {
            return McpTransportContext.create(Map.of(USER_KEY, currentUser.get()));
          } catch (ForbiddenException notSignedIn) {
            return McpTransportContext.EMPTY;
          }
        })
        .build();
  }

  @Bean
  ServletRegistrationBean<HttpServletStatelessServerTransport> mcpServlet(HttpServletStatelessServerTransport transport) {
    return new ServletRegistrationBean<>(transport, "/mcp");
  }

  @Bean(destroyMethod = "close")
  McpStatelessSyncServer mcpServer(HttpServletStatelessServerTransport transport, ObjectMapper objectMapper, McpTools tools,
      @Value("${app.panel-file}") String panelFilePath) {
    Path panelFile = Path.of(panelFilePath);
    Resource panel = Resource.builder()
        .uri(McpTools.PANEL_URI)
        .name("Ask the Dashboard")
        .description("Interactive NMS chart: switch chart type, drill down, pin to the live dashboard.")
        .mimeType(MCP_APP_MIME_TYPE)
        .build();
    // Read on every request, so a rebuilt panel (npm run build in frontend/) shows without a restart.
    SyncResourceSpecification panelSpec = new SyncResourceSpecification(panel, (context, request) -> {
      if (!Files.exists(panelFile)) throw new IllegalStateException("The panel is not built — run `npm run build` in frontend/.");
      try {
        return new ReadResourceResult(List.of(new TextResourceContents(McpTools.PANEL_URI, MCP_APP_MIME_TYPE, Files.readString(panelFile))));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });

    return McpServer.sync(transport)
        .serverInfo("nms-ask-dashboard", "0.2.0")
        .instructions("NMS estate data (farms, devices, issues, connectivity). Call list_datasets first; chart with show_chart.")
        .capabilities(ServerCapabilities.builder().tools(false).resources(false, false).build())
        .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
        .jsonSchemaValidator(new DefaultJsonSchemaValidator(objectMapper))
        .tools(tools.all())
        .resources(panelSpec)
        .build();
  }
}
