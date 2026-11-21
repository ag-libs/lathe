package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

// The built modules with sources Lathe does not read, as an answer names them: of a language it
// does not read (Kotlin, Groovy), or of a Java newer than the JDK the server runs on ("Java 25").
public record UnreadSources(String language, List<String> modules) {
  public UnreadSources {
    ValidCheck.check().notBlank(language, "language").notNull(modules, "modules").validate();
    modules = List.copyOf(modules);
  }
}
