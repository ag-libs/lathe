package io.github.aglibs.lathe.analysis.completion;

enum TypeReferenceRole {
  ORDINARY,
  CONSTRUCTOR,
  CLASS_EXTENDS,
  CLASS_IMPLEMENTS,
  INTERFACE_EXTENDS,
  RECORD_IMPLEMENTS,
  THROWS,
  ANNOTATION,
  // A type argument (a wildcard's bound included) or a type parameter's bound: any reference type,
  // and no keyword, primitive or member declaration, which none of those positions can hold.
  TYPE_ARGUMENT,
  // An array's element type within a type argument (List<int[]>): a type argument that also takes
  // a primitive.
  ARRAY_TYPE_ARGUMENT,
  // A type parameter's bound after the first (<T extends Number & Comparable<T>>): an interface,
  // never a class, a type variable or an array (JLS 4.4).
  ADDITIONAL_BOUND
}
