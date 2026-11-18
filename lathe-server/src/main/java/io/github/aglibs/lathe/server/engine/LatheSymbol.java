package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.Optional;

/**
 * A workspace symbol hit: its name, kind, declaring container, and either its location with a
 * snippet or, for a type from a dependency without sources, that dependency's GAV.
 */
public record LatheSymbol(
    String name, String kind, String container, LatheLocation location, String dependency) {

  public LatheSymbol {
    ValidCheck.check()
        .notNull(name, "name")
        .notNull(kind, "kind")
        .notNull(container, "container")
        .assertTrue(
            (location == null) != (dependency == null), "exactly one of location and dependency")
        .validate();
  }

  public LatheSymbol(
      final String name, final String kind, final String container, final LatheLocation location) {
    this(name, kind, container, location, null);
  }

  public Optional<String> sourcelessDependency() {
    return Optional.ofNullable(dependency);
  }
}
