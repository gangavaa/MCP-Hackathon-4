package com.nms.dashboard.tenant.domain;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/** Where a tenant runs. Stored and shown by its label. */
public enum Environment {
  PRODUCTION("Production"),
  STAGING("Staging"),
  DEVELOPMENT("Development");

  private final String label;

  Environment(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  public static Optional<Environment> fromLabel(String label) {
    return Arrays.stream(values()).filter(e -> e.label.equals(label)).findFirst();
  }

  /** Every label, alphabetically — as error messages list them. */
  public static String labels() {
    return Arrays.stream(values()).map(Environment::label).sorted().collect(Collectors.joining(", "));
  }
}
