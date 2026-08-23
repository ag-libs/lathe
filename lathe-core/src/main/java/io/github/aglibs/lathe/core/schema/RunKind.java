package io.github.aglibs.lathe.core.schema;

/**
 * The kind of a run configuration entry, serialized by name ({@code "MAIN"}/{@code "TEST"}); new
 * kinds append here so existing files stay valid.
 */
public enum RunKind {
  MAIN,
  TEST
}
