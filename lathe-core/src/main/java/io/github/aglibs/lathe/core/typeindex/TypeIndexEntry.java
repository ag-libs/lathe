package io.github.aglibs.lathe.core.typeindex;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

// sourceFileName: the file declaring the type when it is not named after the type's top-level type
// (a secondary top-level type, Helper in Foo.java, and the types nested in it), so its source can
// be found; null otherwise, and in an index of a jar or the JDK. Null keeps it out of index files.
public record TypeIndexEntry(
    String simpleName,
    String binaryName,
    String packageName,
    TypeKind kind,
    boolean typeNameCandidate,
    List<String> directSupertypes,
    String sourceFileName) {

  public TypeIndexEntry {
    ValidCheck.check()
        .notBlank(simpleName, "simpleName")
        .notBlank(binaryName, "binaryName")
        .notNull(packageName, "packageName")
        .notNull(kind, "kind")
        .notNull(directSupertypes, "directSupertypes")
        .nullOrNotBlank(sourceFileName, "sourceFileName")
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
