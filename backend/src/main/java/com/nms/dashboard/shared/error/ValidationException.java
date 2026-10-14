package com.nms.dashboard.shared.error;

import java.util.List;

/** Input a domain object refuses — every problem named at once. 400 on the REST API. */
public class ValidationException extends RuntimeException {

  private final List<String> problems;

  public ValidationException(String subject, List<String> problems) {
    super("Invalid " + subject + ": " + String.join("; ", problems) + ".");
    this.problems = List.copyOf(problems);
  }

  public List<String> problems() {
    return problems;
  }
}
