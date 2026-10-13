package com.nms.askdashboard.query;

/** A question the catalogue cannot answer. The message lists what it can, which is what lets the model correct itself. */
public class QueryException extends RuntimeException {

  public QueryException(String message) {
    super(message);
  }
}
