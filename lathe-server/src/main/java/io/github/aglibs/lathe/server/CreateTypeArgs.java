package io.github.aglibs.lathe.server;

import io.github.aglibs.validcheck.ValidCheck;

// A lathe.types.create request. moduleRel and pkg may be empty (reactor-root module, default
// package), and a module-info's name too: the server names it after the module's base package.
record CreateTypeArgs(String moduleRel, SourceScope kind, String pkg, TypeKind type, String name) {

  CreateTypeArgs {
    ValidCheck.check()
        .notNull(moduleRel, "moduleRel")
        .notNull(kind, "kind")
        .notNull(pkg, "pkg")
        .notNull(type, "type")
        .notNull(name, "name")
        .assertTrue(type == TypeKind.MODULE_INFO || !name.isBlank(), "name")
        .validate();
  }
}
