package io.github.aglibs.lathe.server.run;

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
}
