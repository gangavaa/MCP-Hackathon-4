package com.nms.dashboard.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class McpDiscoveryFilterTest {

  final McpDiscoveryFilter filter = new McpDiscoveryFilter(new ObjectMapper());

  /** Runs the filter; returns whether it marked the request public, and the body the servlet then reads. */
  Object[] run(String body, String authorization) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
    request.setContent(body.getBytes(StandardCharsets.UTF_8));
    if (authorization != null) request.addHeader("Authorization", authorization);
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, new MockHttpServletResponse(), chain);
    String replayed = new String(chain.getRequest().getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    return new Object[] {Boolean.TRUE.equals(request.getAttribute(McpDiscoveryFilter.PUBLIC)), replayed};
  }

  @Test
  void discoveryWithoutATokenIsPublicAndTheBodyIsReplayed() throws Exception {
    String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
    Object[] r = run(body, null);
    assertThat(r[0]).isEqualTo(true);
    assertThat(r[1]).isEqualTo(body);
  }

  @Test
  void anythingThatReadsDataIsNot() throws Exception {
    assertThat(run("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{}}", null)[0]).isEqualTo(false);
    assertThat(run("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"},{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\"}]", null)[0]).isEqualTo(false);
    assertThat(run("not json", null)[0]).isEqualTo(false);
  }

  @Test
  void aRequestWithATokenGoesTheNormalWay() throws Exception {
    // A token, valid or not, is always checked — discovery is only open to requests that bring none.
    assertThat(run("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", "Bearer junk")[0]).isEqualTo(false);
  }
}
