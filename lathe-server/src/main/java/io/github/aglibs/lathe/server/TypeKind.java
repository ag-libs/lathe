package io.github.aglibs.lathe.server;

import java.util.Arrays;

enum TypeKind {
  CLASS("class"),
  INTERFACE("interface"),
  RECORD("record"),
  ENUM("enum"),
  // A JUnit 5 test class matching the buffer's type — rendered as a package-private class, so its
  // wire token is not a Java keyword and newTypeSource branches on it rather than emitting it.
  TEST("test");

  // The wire token the editor sends; for the plain kinds it is also the Java keyword emitted in the
  // skeleton (TEST excepted — see above).
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
