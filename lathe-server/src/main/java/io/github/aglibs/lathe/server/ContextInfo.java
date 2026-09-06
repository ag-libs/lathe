package io.github.aglibs.lathe.server;

import io.github.aglibs.validcheck.ValidCheck;

// The module/scope/package a URI resolves to. moduleRel and pkg may be empty; scope is the wire
// token.
record ContextInfo(String moduleRel, String scope, String pkg) {

  ContextInfo {
    ValidCheck.check()
        .notNull(moduleRel, "moduleRel")
        .notBlank(scope, "scope")
        .notNull(pkg, "pkg")
        .validate();
  }
}
