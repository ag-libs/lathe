package io.github.aglibs.lathe.core.typeindex;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import java.util.Set;

record ClassMetadata(
    ClassAccess access,
    String binaryName,
    List<String> directSupertypes,
    Set<String> referencedTypes,
    // Functional-interface binary names this class converts a lambda / method reference to (the
    // return type of each invokedynamic descriptor); present even when the source never names them.
    Set<String> lambdaTargets) {

  ClassMetadata {
    ValidCheck.check()
        .notNull(access, "access")
        .notBlank(binaryName, "binaryName")
        .notNull(directSupertypes, "directSupertypes")
        .notNull(referencedTypes, "referencedTypes")
        .notNull(lambdaTargets, "lambdaTargets")
        .validate();
    directSupertypes = List.copyOf(directSupertypes);
    referencedTypes = Set.copyOf(referencedTypes);
    lambdaTargets = Set.copyOf(lambdaTargets);
  }
}
