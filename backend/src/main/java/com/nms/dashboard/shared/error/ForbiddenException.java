package com.nms.dashboard.shared.error;

/** Signed in, but not allowed to do this. 403 on the REST API. */
public class ForbiddenException extends RuntimeException {

  public ForbiddenException(String message) {
    super(message);
  }
}
