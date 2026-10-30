package io.github.aglibs.lathe.server.analysis;

public enum ReferenceRole {
  DECLARATION,
  IMPORT,
  // A static import that also names members outside the renamed family: it must stay as it is.
  SHARED_STATIC_IMPORT,
  INVOCATION,
  TYPE_USE,
  READ,
  WRITE
}
