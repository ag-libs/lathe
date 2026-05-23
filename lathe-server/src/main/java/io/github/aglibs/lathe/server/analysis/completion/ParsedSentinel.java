package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.validcheck.ValidCheck;

record ParsedSentinel(
    boolean valid,
    String prefix,
    String receiverText,
    SentinelContext sentinelContext,
    String enclosingClass,
    String enclosingMethod,
    int argIndex,
    String enclosingReceiver,
    String enclosingMethodName,
    int docVersion) {

  ParsedSentinel {
    ValidCheck.check()
        .notNull(prefix, "prefix")
        .nullOrNotEmpty(receiverText, "receiverText")
        .when(valid, v -> v.notNull(sentinelContext, "sentinelContext"))
        .when(
            !valid,
            v ->
                v.isNull(sentinelContext, "sentinelContext")
                    .isNull(enclosingClass, "enclosingClass")
                    .isNull(enclosingMethod, "enclosingMethod"))
        .validate();
  }

  static ParsedSentinel invalid(
      final String prefix, final String receiverText, final int docVersion) {
    return new ParsedSentinel(
        false, prefix, receiverText, null, null, null, -1, null, null, docVersion);
  }
}
