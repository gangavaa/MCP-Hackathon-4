package com.nms.dashboard.shared.persistence;

import com.nms.dashboard.shared.domain.EstateClock;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** {@link EstateClock} read from the {@code meta} row the seed writes. */
@Component
public class JdbcEstateClock implements EstateClock {

  private final JdbcTemplate jdbc;

  public JdbcEstateClock(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Instant now() {
    return jdbc.queryForObject("SELECT anchor FROM meta LIMIT 1", Timestamp.class).toInstant();
  }
}
