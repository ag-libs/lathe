package io.github.aglibs.lathe.core.typeindex;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

public record TypeIndexFile(String schema, TypeIndexOrigin origin, List<TypeIndexEntry> types) {

  public TypeIndexFile {
    ValidCheck.check()
        .notBlank(schema, "schema")
        .notNull(origin, "origin")
        .notNull(types, "types")
        .validate();
  }
}
