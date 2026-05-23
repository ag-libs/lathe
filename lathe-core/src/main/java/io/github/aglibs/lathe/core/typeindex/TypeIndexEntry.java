package io.github.aglibs.lathe.core.typeindex;

import io.github.aglibs.validcheck.ValidCheck;

public record TypeIndexEntry(
    String simpleName, String qualifiedName, String packageName, TypeKind kind) {

  public TypeIndexEntry {
    ValidCheck.check()
        .notBlank(simpleName, "simpleName")
        .notBlank(qualifiedName, "qualifiedName")
        .notNull(packageName, "packageName")
        .notNull(kind, "kind")
        .validate();
  }
}
