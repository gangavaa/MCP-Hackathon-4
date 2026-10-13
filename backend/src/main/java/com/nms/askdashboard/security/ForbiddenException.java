package com.nms.askdashboard.security;

/** Signed in, but not allowed to do this. 403 on the REST API; a tool error over MCP. */
public class ForbiddenException extends RuntimeException {

  public ForbiddenException(String message) {
    super(message);
  }
}
