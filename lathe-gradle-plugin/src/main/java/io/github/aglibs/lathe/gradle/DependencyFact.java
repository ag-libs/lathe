package io.github.aglibs.lathe.gradle;

import io.github.aglibs.validcheck.ValidCheck;

// One external library a project compiles against: its jar and, when the repository has one, its
// -sources jar (null otherwise).
record DependencyFact(String group, String name, String version, String jar, String sourcesJar) {

  DependencyFact {
    ValidCheck.check()
        .notBlank(group, "group")
        .notBlank(name, "name")
        .notBlank(version, "version")
        .notBlank(jar, "jar")
        .validate();
  }

  // From group:name:version coordinates, as the facts task receives them.
  static DependencyFact of(final String gav, final String jar, final String sourcesJar) {
    final String[] parts = gav.split(":");
    return new DependencyFact(parts[0], parts[1], parts[2], jar, sourcesJar);
  }

  String gav() {
    return "%s:%s:%s".formatted(group, name, version);
  }
}
