package com.nms.dashboard.shared.error;

/** A change that clashes with the state of things: a taken name, a tenant that still has farms. 409 on the REST API. */
public class ConflictException extends RuntimeException {

  public ConflictException(String message) {
    super(message);
  }
}
