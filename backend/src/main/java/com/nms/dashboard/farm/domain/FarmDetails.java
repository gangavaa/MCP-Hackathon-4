package com.nms.dashboard.farm.domain;

/**
 * What a farm is registered or edited with, as given — unchecked; {@link Farm} checks it. In an edit, a null field
 * is left as it is. {@code address} is the farm's town.
 */
public record FarmDetails(String tenantId, String name, String farmName, String contractUser, String address,
    String country, Double latitude, Double longitude, String version, String protocol, String connectivityStatus,
    Integer animals) {

  public static final FarmDetails NONE = new FarmDetails(null, null, null, null, null, null, null, null, null, null, null, null);
}
