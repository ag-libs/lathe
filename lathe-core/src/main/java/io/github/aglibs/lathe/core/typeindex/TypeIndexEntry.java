package io.github.aglibs.lathe.core.typeindex;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

public record TypeIndexEntry(
    String simpleName,
    String binaryName,
    String packageName,
    TypeKind kind,
    boolean typeNameCandidate,
    List<String> directSupertypes) {

  public TypeIndexEntry {
    ValidCheck.check()
        .notBlank(simpleName, "simpleName")
        .notBlank(binaryName, "binaryName")
        .notNull(packageName, "packageName")
        .notNull(kind, "kind")
        .notNull(directSupertypes, "directSupertypes")
        .validate();
    directSupertypes = List.copyOf(directSupertypes);
  }

  public String qualifiedName() {
    return binaryName.replace('$', '.');
  }

  public String className() {
    return packageName.isEmpty() ? binaryName : binaryName.substring(packageName.length() + 1);
  }

  public boolean isTopLevel() {
    return !binaryName.contains("$");
  }
}
