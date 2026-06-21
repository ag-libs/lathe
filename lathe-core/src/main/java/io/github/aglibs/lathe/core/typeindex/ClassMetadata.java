package io.github.aglibs.lathe.core.typeindex;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

record ClassMetadata(ClassAccess access, String binaryName, List<String> directSupertypes) {

  ClassMetadata {
    ValidCheck.check()
        .notNull(access, "access")
        .notBlank(binaryName, "binaryName")
        .notNull(directSupertypes, "directSupertypes")
        .validate();
    directSupertypes = List.copyOf(directSupertypes);
  }
}
