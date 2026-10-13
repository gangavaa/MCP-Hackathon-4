package com.nms.askdashboard.api;

import org.springframework.http.HttpStatus;

/** A request the API refuses, with the status to answer — 400 for invalid input, 409 for a conflict. */
public class ApiException extends RuntimeException {

  private final HttpStatus status;

  public ApiException(HttpStatus status, String message) {
    super(message);
    this.status = status;
  }

  public HttpStatus status() {
    return status;
  }
}
