package io.github.aglibs.lathe.core.typeindex;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import java.util.Set;

record ClassMetadata(
    ClassAccess access,
    String binaryName,
    List<String> directSupertypes,
    Set<String> referencedTypes) {

  ClassMetadata {
    ValidCheck.check()
        .notNull(access, "access")
        .notBlank(binaryName, "binaryName")
        .notNull(directSupertypes, "directSupertypes")
        .notNull(referencedTypes, "referencedTypes")
        .validate();
    directSupertypes = List.copyOf(directSupertypes);
    referencedTypes = Set.copyOf(referencedTypes);
  }
}
