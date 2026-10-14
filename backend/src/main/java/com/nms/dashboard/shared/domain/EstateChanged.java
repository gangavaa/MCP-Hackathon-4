package com.nms.dashboard.shared.domain;

/**
 * A domain event: the estate's tenants or farms changed. Published by the tenant and farm contexts; the analytics
 * context listens, so the dimension values it offers (tenant and farm names) are read afresh.
 */
public interface EstateChanged {}
