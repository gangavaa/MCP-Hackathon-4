package com.nms.askdashboard.model;

/** {@code lastDays} days ending at the data's anchor, shifted back {@code offsetDays} ("the week before"). */
public record TimeRange(int lastDays, Integer offsetDays) {

  public int offset() {
    return offsetDays == null ? 0 : offsetDays;
  }
}
