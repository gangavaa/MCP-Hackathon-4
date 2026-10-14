package com.nms.dashboard.shared.domain;

import com.nms.dashboard.shared.error.ValidationException;
import java.util.ArrayList;
import java.util.List;

/** Collects what is wrong with an input, so a domain object can name every problem at once instead of the first. */
public final class Problems {

  private final String subject;
  private final List<String> problems = new ArrayList<>();

  public Problems(String subject) {
    this.subject = subject;
  }

  public Problems add(String problem) {
    problems.add(problem);
    return this;
  }

  /** The value trimmed; blank or longer than {@code max} is a problem. */
  public String required(String field, String value, int max) {
    String v = value == null ? "" : value.trim();
    if (v.isEmpty()) add(field + " is required");
    else if (v.length() > max) add(field + " is at most " + max + " characters");
    return v;
  }

  /** The value trimmed, or empty; longer than {@code max} is a problem. */
  public String optional(String field, String value, int max) {
    String v = value == null ? "" : value.trim();
    if (v.length() > max) add(field + " is at most " + max + " characters");
    return v;
  }

  public void throwIfAny() {
    if (!problems.isEmpty()) throw new ValidationException(subject, problems);
  }
}
