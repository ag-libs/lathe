package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.LatheLayout;
import java.util.Arrays;

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
    return Arrays.stream(values())
        .filter(scope -> scope.wire.equals(wire))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown source scope: " + wire));
  }
}
