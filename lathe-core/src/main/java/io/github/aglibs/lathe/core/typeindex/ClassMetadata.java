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
    Set<String> lambdaTargets,
    // The SourceFile attribute's name; null when not read (only a reactor scan reads it) or absent.
    String sourceFile) {

  ClassMetadata {
    ValidCheck.check()
        .notNull(access, "access")
        .notBlank(binaryName, "binaryName")
        .notNull(directSupertypes, "directSupertypes")
        .notNull(referencedTypes, "referencedTypes")
        .notNull(lambdaTargets, "lambdaTargets")
        .nullOrNotBlank(sourceFile, "sourceFile")
        .validate();
    directSupertypes = List.copyOf(directSupertypes);
    referencedTypes = Set.copyOf(referencedTypes);
    lambdaTargets = Set.copyOf(lambdaTargets);
  }

  // A record has no access flag of its own: javac makes it extend java.lang.Record, which nothing
  // else may.
  TypeKind kind() {
    final TypeKind flagged = access.kind();
    return flagged == TypeKind.CLASS
            && !directSupertypes.isEmpty()
            && "java.lang.Record".equals(directSupertypes.get(0))
        ? TypeKind.RECORD
        : flagged;
  }
}
