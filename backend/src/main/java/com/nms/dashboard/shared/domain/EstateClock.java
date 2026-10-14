package com.nms.dashboard.shared.domain;

import java.time.Instant;

/** "Now" for the estate: the anchor its data was seeded against, so new records sit on the same timeline. */
public interface EstateClock {

  Instant now();
}
