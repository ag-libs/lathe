package io.github.aglibs.lathe.server;

import io.github.aglibs.validcheck.ValidCheck;

// A resource the finder can open. `name` is the match/display path; `origin` is "reactor:<module>"
// or "dep:<gav>". A FILE entry has an editable on-disk `path`; a JAR entry has `jar` + `entry` for
// single-entry extract-on-open. The unused field is empty.
record ResourceEntry(
    String name, String origin, String kind, String path, String jar, String entry) {

  static final String FILE = "FILE";
  static final String JAR = "JAR";

  ResourceEntry {
    ValidCheck.check()
        .notBlank(name, "name")
        .notBlank(origin, "origin")
        .notBlank(kind, "kind")
        .notNull(path, "path")
        .notNull(jar, "jar")
        .notNull(entry, "entry")
        .validate();
  }

  static ResourceEntry reactor(final String name, final String module, final String path) {
    return new ResourceEntry(name, "reactor:%s".formatted(module), FILE, path, "", "");
  }

  static ResourceEntry dependency(final String gav, final String jar, final String entry) {
    return new ResourceEntry(entry, "dep:%s".formatted(gav), JAR, "", jar, entry);
  }
}
