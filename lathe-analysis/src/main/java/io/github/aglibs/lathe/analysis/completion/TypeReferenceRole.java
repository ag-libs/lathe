package io.github.aglibs.lathe.analysis.completion;

enum TypeReferenceRole {
  ORDINARY,
  CONSTRUCTOR,
  CLASS_EXTENDS,
  CLASS_IMPLEMENTS,
  INTERFACE_EXTENDS,
  RECORD_IMPLEMENTS,
  THROWS,
  ANNOTATION
}
