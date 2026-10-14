package com.nms.dashboard.mcp.api;

/**
 * The API said no, or could not be reached. The message is written for the model — it reaches it as a tool error —
 * so a 400 keeps the API's own words, which name the fields that do exist.
 */
public class NmsApiException extends RuntimeException {

  public NmsApiException(String message) {
    super(message);
  }

  public NmsApiException(String message, Throwable cause) {
    super(message, cause);
  }
}
