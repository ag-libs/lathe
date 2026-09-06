package io.github.aglibs.lathe.server;

import io.github.aglibs.validcheck.ValidCheck;

// A lathe.createType request. moduleRel and pkg may be empty (reactor-root module, default
// package).
record CreateTypeArgs(String moduleRel, SourceScope kind, String pkg, TypeKind type, String name) {

  CreateTypeArgs {
    ValidCheck.check()
        .notNull(moduleRel, "moduleRel")
        .notNull(kind, "kind")
        .notNull(pkg, "pkg")
        .notNull(type, "type")
        .notBlank(name, "name")
        .validate();
  }
}
