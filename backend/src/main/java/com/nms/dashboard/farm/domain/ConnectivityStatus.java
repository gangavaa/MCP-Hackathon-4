package com.nms.dashboard.farm.domain;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/** nms-core-ui's connectivity vocabulary. A farm that has not reported yet is {@link #UNKNOWN}. */
public enum ConnectivityStatus {
  ONLINE, PARTIALLY_ONLINE, WARNING, OFFLINE, UNKNOWN;

  public static Optional<ConnectivityStatus> fromLabel(String label) {
    return Arrays.stream(values()).filter(s -> s.name().equals(label)).findFirst();
  }

  public static String labels() {
    return Arrays.stream(values()).map(Enum::name).sorted().collect(Collectors.joining(", "));
  }
}
