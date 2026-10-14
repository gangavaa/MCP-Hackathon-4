package com.nms.dashboard.farm.domain;

import com.nms.dashboard.shared.domain.EstateChanged;

/** A farm was registered, edited, moved or deleted. */
public record FarmChanged(String farmId) implements EstateChanged {}
