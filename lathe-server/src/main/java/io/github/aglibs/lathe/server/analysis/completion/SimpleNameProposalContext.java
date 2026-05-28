package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.validcheck.ValidCheck;
import javax.lang.model.type.TypeMirror;

record SimpleNameProposalContext(
    String enclosingClass,
    String enclosingMethod,
    String prefix,
    int cursorOffset,
    TypeMirror expectedParamType,
    boolean inValueContext) {

  SimpleNameProposalContext {
    ValidCheck.check()
        .notBlank(enclosingClass, "enclosingClass")
        .notNull(prefix, "prefix")
        .isNonNegative(cursorOffset, "cursorOffset")
        .nullOrNotBlank(enclosingMethod, "enclosingMethod")
        .validate();
  }
}
