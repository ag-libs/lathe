package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

/**
 * Implementations of an interface (or overrides of a method): the true total, whether the list was
 * capped, and the enriched hits.
 */
public record LatheImplementations(
    int total, boolean truncated, List<LatheLocation> implementations) {

  public LatheImplementations {
    ValidCheck.check().notNull(implementations, "implementations").validate();
    implementations = List.copyOf(implementations);
  }
}
