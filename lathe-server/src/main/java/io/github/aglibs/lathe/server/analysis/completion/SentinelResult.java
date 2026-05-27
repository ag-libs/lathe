package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.validcheck.ValidCheck;

record SentinelResult(
    String prefix,
    int tokenStart,
    String receiverText,
    SentinelInjector.Context context,
    boolean hasDot,
    String injectedContent) {

  SentinelResult {
    ValidCheck.check()
        .notNull(prefix, "prefix")
        .isNonNegative(tokenStart, "tokenStart")
        .notNull(context, "context")
        .notNull(injectedContent, "injectedContent")
        .nullOrNotEmpty(receiverText, "receiverText")
        .validate();
  }
}
