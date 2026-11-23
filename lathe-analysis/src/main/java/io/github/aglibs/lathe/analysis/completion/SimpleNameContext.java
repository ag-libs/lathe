package io.github.aglibs.lathe.analysis.completion;

import io.github.aglibs.validcheck.ValidCheck;

record SimpleNameContext(
    String enclosingClass,
    String enclosingMethod,
    String prefix,
    int cursorOffset,
    int snapshotCursorOffset,
    SemanticCompletionContext semanticContext) {

  SimpleNameContext {
    ValidCheck.check()
        .notBlank(enclosingClass, "enclosingClass")
        .notNull(prefix, "prefix")
        .isNonNegative(cursorOffset, "cursorOffset")
        .isNonNegative(snapshotCursorOffset, "snapshotCursorOffset")
        .nullOrNotBlank(enclosingMethod, "enclosingMethod")
        .notNull(semanticContext, "semanticContext")
        .validate();
  }
}
