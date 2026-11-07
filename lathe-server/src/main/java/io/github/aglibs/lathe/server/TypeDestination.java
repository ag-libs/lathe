package io.github.aglibs.lathe.server;

import io.github.aglibs.validcheck.ValidCheck;

// Where a new type may go, a lathe.types.destinations entry: a module, the scope's wire token, and
// a package of it.
record TypeDestination(String module, String scope, String pkg) {

  TypeDestination {
    ValidCheck.check()
        .notNull(module, "module")
        .notBlank(scope, "scope")
        .notBlank(pkg, "pkg")
        .validate();
  }
}
