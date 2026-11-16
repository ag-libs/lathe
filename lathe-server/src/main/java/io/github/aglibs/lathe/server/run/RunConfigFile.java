package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * On-disk shape of a run-config layer: auto-applied {@code defaults} and name-keyed {@code
 * configs}. Gson maps this directly. {@code configs} keeps insertion order so completion lists in
 * file order.
 */
record RunConfigFile(List<RunConfigEntry> defaults, Map<String, RunConfigEntry> configs) {

  RunConfigFile {
    defaults = defaults != null ? List.copyOf(defaults) : List.of();
    configs =
        configs != null ? Collections.unmodifiableMap(new LinkedHashMap<>(configs)) : Map.of();
  }

  static RunConfigFile empty() {
    return new RunConfigFile(List.of(), Map.of());
  }

  // A layer the user created but left empty holds no entries; Json.read takes an empty file as
  // damaged.
  static RunConfigFile readOrEmpty(final Path file) throws IOException {
    if (!Files.exists(file) || Files.size(file) == 0) {
      return empty();
    }

    return Json.read(file, RunConfigFile.class);
  }
}
