package com.nms.dashboard.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The MCP server Claude talks to, at {@code /mcp}: a gateway in front of the NMS REST API. It holds no data and no
 * SQL — every tool is one or two API calls, made with the signed-in user's own token, so the API decides what they see.
 */
@SpringBootApplication
public class McpServerApplication {

  public static void main(String[] args) {
    SpringApplication.run(McpServerApplication.class, args);
  }
}
