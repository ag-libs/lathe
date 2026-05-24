package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.validcheck.ValidCheck;

record SimpleNameProposalContext(
    String enclosingClass, String enclosingMethod, String prefix, int cursorOffset) {

  SimpleNameProposalContext {
    ValidCheck.check()
        .notBlank(enclosingClass, "enclosingClass")
        .notNull(prefix, "prefix")
        .isNonNegative(cursorOffset, "cursorOffset")
        .nullOrNotBlank(enclosingMethod, "enclosingMethod")
        .validate();
  }
}
