package com.nms.dashboard.tenant.domain;

import com.nms.dashboard.shared.domain.Problems;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A tenant — an NMS customer: a name unique in the estate, the environment it runs in, the specie its farms keep,
 * and its brand colour. Immutable: {@link #create} and {@link #edit} return a valid tenant or name every problem.
 */
public record Tenant(String id, String name, Environment environment, Specie specie, String color) {

  public static final String DEFAULT_COLOR = "#0075C3";
  static final int MAX_NAME = 80;
  private static final Pattern COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");

  /** A new tenant; a missing colour becomes the default. */
  public static Tenant create(String id, String name, String environment, String specie, String color) {
    return validated(id, name, environment, specie, color == null ? DEFAULT_COLOR : color);
  }

  /** This tenant with the given changes; a null leaves that field as it is. */
  public Tenant edit(String name, String environment, String specie, String color) {
    return validated(id,
        name != null ? name : this.name,
        environment != null ? environment : this.environment.label(),
        specie != null ? specie : this.specie.label(),
        color != null ? color : this.color);
  }

  /** Whether an edit changed what the tenant's farms take from it. */
  public boolean classifiesFarmsDifferentlyFrom(Tenant before) {
    return environment != before.environment || specie != before.specie;
  }

  private static Tenant validated(String id, String name, String environment, String specie, String color) {
    Problems problems = new Problems("tenant");
    String n = problems.required("name", name, MAX_NAME);
    Optional<Environment> env = Environment.fromLabel(environment);
    if (env.isEmpty()) problems.add("environment must be one of " + Environment.labels());
    Optional<Specie> sp = Specie.fromLabel(specie);
    if (sp.isEmpty()) problems.add("specie must be one of " + Specie.labels());
    String c = color == null ? "" : color.trim();
    if (!COLOR.matcher(c).matches()) problems.add("color must be a hex colour such as #23885C");
    problems.throwIfAny();
    return new Tenant(id, n, env.get(), sp.get(), c.toUpperCase());
  }
}
