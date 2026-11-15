package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

// The built modules with sources of a language Lathe does not read (Kotlin, Groovy), as an answer
// names them.
public record UnreadSources(String language, List<String> modules) {
  public UnreadSources {
    ValidCheck.check().notBlank(language, "language").notNull(modules, "modules").validate();
    modules = List.copyOf(modules);
  }
}
