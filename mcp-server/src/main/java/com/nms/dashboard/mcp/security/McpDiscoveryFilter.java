package com.nms.dashboard.mcp.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets an MCP client discover the server before anyone signs in. Claude Desktop sends {@code initialize} and
 * {@code tools/list} the moment it starts; if those needed a token, the sign-in page would open with the app instead
 * of with the first question about NMS. None of these methods returns estate data — the tool list, the panel's HTML,
 * a ping — so a request without an {@code Authorization} header that calls only one of them is marked public.
 * Everything else, {@code tools/call} above all, still needs a valid token (McpSecurityConfig).
 *
 * <p>Runs before the security filter chain. It reads the body once and hands the servlet a copy, so the MCP
 * transport reads it again unchanged.
 */
public class McpDiscoveryFilter extends OncePerRequestFilter {

  /** Request attribute McpSecurityConfig's matcher checks. */
  public static final String PUBLIC = McpDiscoveryFilter.class.getName() + ".public";

  /** JSON-RPC methods that reveal what the server offers, never what the estate holds. */
  static final Set<String> DISCOVERY = Set.of(
      "initialize", "notifications/initialized", "ping", "tools/list", "resources/list", "resources/templates/list",
      "resources/read", "prompts/list", "notifications/cancelled");

  private static final int MAX_BODY = 64 * 1024; // discovery requests are tiny; anything larger goes the normal way

  private final ObjectMapper json;

  public McpDiscoveryFilter(ObjectMapper json) {
    this.json = json;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !("POST".equals(request.getMethod()) && "/mcp".equals(request.getRequestURI()) && request.getHeader("Authorization") == null);
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
    if (body.length <= MAX_BODY && isDiscovery(body)) request.setAttribute(PUBLIC, Boolean.TRUE);
    chain.doFilter(new ReplayedBody(request, body), response);
  }

  /** One JSON-RPC message (not a batch) whose method is a discovery method. */
  private boolean isDiscovery(byte[] body) {
    try {
      JsonNode message = json.readTree(body);
      return message != null && message.isObject() && DISCOVERY.contains(message.path("method").asText(""));
    } catch (IOException notJson) {
      return false;
    }
  }

  /** The request, with its body readable again. */
  private static final class ReplayedBody extends HttpServletRequestWrapper {

    private final byte[] body;

    ReplayedBody(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream in = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
          return in.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
          return in.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException("synchronous only");
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }
  }
}
