package com.nms.askdashboard.config;

import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Postgres either way: the server DATABASE_URL names (docker compose on the demo machine), or — when it is unset — a
 * real Postgres started in-process from bundled binaries, its data kept in {@code app.embedded-data-dir}.
 */
@Configuration
public class DatabaseConfig {

  private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

  @Bean(destroyMethod = "")
  DataSource dataSource(
      @Value("${DATABASE_URL:}") String databaseUrl,
      @Value("${app.embedded-data-dir}") String embeddedDataDirPath,
      @Value("${app.embedded-port}") int embeddedPort)
      throws IOException {
    Path embeddedDataDir = Path.of(embeddedDataDirPath);
    if (StringUtils.hasText(databaseUrl)) {
      log.info("Database: Postgres at {}", databaseUrl.replaceAll("//[^@]*@", "//***@"));
      return fromUrl(databaseUrl);
    }
    log.info("Database: embedded Postgres in {} (set DATABASE_URL to use a server instead)", embeddedDataDir.toAbsolutePath());
    EmbeddedPostgres pg = EmbeddedPostgres.builder()
        .setDataDirectory(embeddedDataDir)
        .setCleanDataDirectory(false)
        .setPort(embeddedPort)
        .start();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        pg.close();
      } catch (IOException ignored) {
        // shutting down anyway
      }
    }));
    return pg.getPostgresDatabase();
  }

  /** Accepts the postgres://user:pass@host:port/db form docker compose and .env.example use. */
  static DataSource fromUrl(String url) {
    HikariDataSource ds = new HikariDataSource();
    if (url.startsWith("jdbc:")) {
      ds.setJdbcUrl(url);
      return ds;
    }
    URI uri = URI.create(url);
    ds.setJdbcUrl("jdbc:postgresql://" + uri.getHost() + ":" + (uri.getPort() > 0 ? uri.getPort() : 5432) + uri.getPath());
    if (uri.getUserInfo() != null) {
      String[] userInfo = uri.getUserInfo().split(":", 2);
      ds.setUsername(userInfo[0]);
      if (userInfo.length > 1) ds.setPassword(userInfo[1]);
    }
    ds.setMaximumPoolSize(5);
    return ds;
  }
}
