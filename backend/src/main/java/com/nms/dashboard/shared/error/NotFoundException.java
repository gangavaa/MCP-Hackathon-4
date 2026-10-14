package com.nms.dashboard.shared.error;

/** No such tenant or farm — or none the user may see, which they can't tell apart. 404 on the REST API. */
public class NotFoundException extends RuntimeException {

  public NotFoundException(String message) {
    super(message);
  }
}
