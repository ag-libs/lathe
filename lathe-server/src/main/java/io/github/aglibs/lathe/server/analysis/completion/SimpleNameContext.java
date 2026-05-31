package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.validcheck.ValidCheck;

record SimpleNameContext(
    String enclosingClass,
    String enclosingMethod,
    String prefix,
    int cursorOffset,
    SemanticCompletionContext semanticContext) {

  SimpleNameContext {
    ValidCheck.check()
        .notBlank(enclosingClass, "enclosingClass")
        .notNull(prefix, "prefix")
        .isNonNegative(cursorOffset, "cursorOffset")
        .nullOrNotBlank(enclosingMethod, "enclosingMethod")
        .notNull(semanticContext, "semanticContext")
        .validate();
  }
}
