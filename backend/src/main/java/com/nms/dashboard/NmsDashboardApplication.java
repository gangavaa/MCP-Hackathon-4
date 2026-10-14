package com.nms.dashboard;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * NMS Dashboard: the REST API the dashboard reads and the Angular frontend, served from one process — the
 * only one that touches Postgres.
 *
 * <p>Claude reaches it through the separate MCP server ({@code mcp-server/}), which calls this API like any other
 * client, with the signed-in user's token.
 */
@SpringBootApplication
public class NmsDashboardApplication {

  public static void main(String[] args) {
    // Every time window is in UTC, from the anchor (midnight UTC). The Postgres driver gives each session the JVM's
    // zone, and a DATE column (connectivity_snapshots.day) is compared in the session's zone — so the JVM runs in UTC,
    // or a window shifts by the machine's offset and "yesterday" comes back empty.
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    SpringApplication.run(NmsDashboardApplication.class, args);
  }
}
