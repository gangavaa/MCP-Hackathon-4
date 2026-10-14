package com.nms.dashboard.farm.domain;

/** What a farm holds and would take with it if deleted. */
public record FarmHoldings(int devices, int issues, int connectivityDays) {

  public boolean isEmpty() {
    return devices + issues + connectivityDays == 0;
  }
}
