package com.nms.dashboard.mcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The chat panel — the MCP App, built into one HTML file by {@code npm run build} in frontend/ — served as the
 * {@link McpTools#PANEL_URI} resource. Read on every request, so a rebuilt panel shows without a restart. Holds no data.
 */
@Component
public class McpPanel {

  static final String NOT_BUILT = "The panel is not built — run npm run build in frontend/.";

  private final Path file;

  public McpPanel(@Value("${app.panel-file}") String file) {
    this.file = Path.of(file);
  }

  /** The panel's HTML, or empty when it has not been built. */
  Optional<String> html() {
    if (!Files.exists(file)) return Optional.empty();
    try {
      return Optional.of(Files.readString(file));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
