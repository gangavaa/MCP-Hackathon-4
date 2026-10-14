package com.nms.dashboard.farm.domain;

/** Where a farm is: its town (the API's {@code address}), country and coordinates. */
public record Location(String town, String country, double latitude, double longitude) {}
