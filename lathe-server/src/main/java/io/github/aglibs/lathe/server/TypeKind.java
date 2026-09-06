package io.github.aglibs.lathe.server;

import java.util.Arrays;

enum TypeKind {
  CLASS("class"),
  INTERFACE("interface"),
  RECORD("record"),
  ENUM("enum");

  // The wire token the editor sends, which is also the Java keyword emitted in the skeleton.
  final String keyword;

  TypeKind(final String keyword) {
    this.keyword = keyword;
  }

  static TypeKind fromWire(final String wire) {
    return Arrays.stream(values())
        .filter(kind -> kind.keyword.equals(wire))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown type flavour: " + wire));
  }
}
