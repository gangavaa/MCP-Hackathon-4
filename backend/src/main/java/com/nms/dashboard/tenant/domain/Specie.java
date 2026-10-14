package com.nms.dashboard.tenant.domain;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/** The animals a tenant's farms keep. Stored and shown by its label. */
public enum Specie {
  DAIRY("Dairy"),
  FEEDLOT("Feedlot"),
  POULTRY("Poultry");

  private final String label;

  Specie(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  public static Optional<Specie> fromLabel(String label) {
    return Arrays.stream(values()).filter(s -> s.label.equals(label)).findFirst();
  }

  /** Every label, alphabetically — as error messages list them. */
  public static String labels() {
    return Arrays.stream(values()).map(Specie::label).sorted().collect(Collectors.joining(", "));
  }
}
