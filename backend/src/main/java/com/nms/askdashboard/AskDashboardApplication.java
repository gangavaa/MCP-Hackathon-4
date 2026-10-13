package com.nms.askdashboard;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * NMS Ask the Dashboard: the REST API the dashboard reads, the MCP server Claude talks to (at {@code /mcp}), and
 * the Angular frontend, all served from one process — the only one that touches Postgres.
 */
@SpringBootApplication
public class AskDashboardApplication {

  public static void main(String[] args) {
    System.setProperty("java.awt.headless", "true"); // chart PNGs are drawn without a display
    // Every time window is in UTC, from the anchor (midnight UTC). The Postgres driver gives each session the JVM's
    // zone, and a DATE column (connectivity_snapshots.day) is compared in the session's zone — so the JVM runs in UTC,
    // or a window shifts by the machine's offset and "yesterday" comes back empty.
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    SpringApplication.run(AskDashboardApplication.class, args);
  }
}
