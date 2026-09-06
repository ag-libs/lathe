package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.LatheLayout;
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

  static SourceScope ofSourceTree(final String sourceTree) {
    return find(scope -> scope.sourceTree.equals(sourceTree), "unknown source tree: " + sourceTree);
  }

  private static SourceScope find(final Predicate<SourceScope> match, final String error) {
    return Arrays.stream(values())
        .filter(match)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException(error));
  }
}
