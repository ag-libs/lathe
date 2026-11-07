package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import java.util.Arrays;
import java.util.function.Predicate;

enum SourceScope {
  MAIN("main", LatheLayout.CLASSES_DIR),
  TEST("test", LatheLayout.TEST_CLASSES_DIR);

  final String wire;
  final String sourceTree;

  SourceScope(final String wire, final String sourceTree) {
    this.wire = wire;
    this.sourceTree = sourceTree;
  }

  static SourceScope fromWire(final String wire) {
    return find(scope -> scope.wire.equals(wire), "unknown source scope: " + wire);
  }

  // A module can compile more trees than main and test (a multi-release jar's versions/21, an extra
  // execution's output, a Gradle source set); its params say which are test code.
  static SourceScope of(final ModuleSourceConfig config) {
    return config.isTestTree() ? TEST : MAIN;
  }

  private static SourceScope find(final Predicate<SourceScope> match, final String error) {
    return Arrays.stream(values())
        .filter(match)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException(error));
  }
}
