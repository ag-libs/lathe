package io.github.aglibs.lathe.server;

import io.github.aglibs.validcheck.ValidCheck;

// A package under a module source root. pkg may be empty (default package); scope is the wire
// token.
record PackageEntry(String pkg, String scope) {

  PackageEntry {
    ValidCheck.check().notNull(pkg, "pkg").notBlank(scope, "scope").validate();
  }
}
