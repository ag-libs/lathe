package io.github.aglibs.lathe.server.run;

public enum RunnableKind {
  MAIN,
  TEST_METHOD,
  TEST_CLASS,
  TEST_PACKAGE,
  // Appended, never inserted: lsp4j serializes this enum by ordinal, and the clients
  // (lathe.run, lathe.neotest, explore.py) key off the 0-3 ordinals above. A new value must
  // take the next ordinal so existing wire values stay stable; unknown ordinals are ignored by
  // the neotest adapter. MAIN_CLASS marks the class enclosing a main, for a class-level gutter.
  MAIN_CLASS
}
