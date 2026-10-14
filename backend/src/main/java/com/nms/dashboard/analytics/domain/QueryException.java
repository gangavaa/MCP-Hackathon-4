package com.nms.dashboard.analytics.domain;

/** A question the catalogue cannot answer. The message lists what it can, so the caller can correct the request. */
public class QueryException extends RuntimeException {

  public QueryException(String message) {
    super(message);
  }
}
