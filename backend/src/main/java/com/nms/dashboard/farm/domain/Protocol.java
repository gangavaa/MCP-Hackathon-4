package com.nms.dashboard.farm.domain;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/** How a farm's devices report. Stored and shown by its label. */
public enum Protocol {
  IOT("IoT"),
  NO_IOT("NoIoT");

  private final String label;

  Protocol(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  public static Optional<Protocol> fromLabel(String label) {
    return Arrays.stream(values()).filter(p -> p.label.equals(label)).findFirst();
  }

  public static String labels() {
    return Arrays.stream(values()).map(Protocol::label).sorted().collect(Collectors.joining(", "));
  }
}
