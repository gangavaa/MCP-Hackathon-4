package com.nms.dashboard.tenant.domain;

import com.nms.dashboard.shared.domain.EstateChanged;

/** A tenant was created, edited or deleted. */
public record TenantChanged(String tenantId) implements EstateChanged {}
