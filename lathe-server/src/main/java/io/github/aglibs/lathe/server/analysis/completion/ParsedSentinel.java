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
    int lambdaParamIndex,
    String declaredTypeText,
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
        false, prefix, receiverText, null, null, null, -1, null, null, -1, null, docVersion);
  }

  static ParsedSentinel valid(
      final SentinelResult injected, final SentinelContext context, final int docVersion) {
    return valid(injected, context, null, null, -1, null, null, -1, null, docVersion);
  }

  static ParsedSentinel valid(
      final SentinelResult injected,
      final SentinelContext context,
      final String enclosingClass,
      final String enclosingMethod,
      final int argIndex,
      final String enclosingReceiver,
      final String enclosingMethodName,
      final int lambdaParamIndex,
      final String declaredTypeText,
      final int docVersion) {
    return new ParsedSentinel(
        true,
        injected.prefix(),
        injected.receiverText(),
        context,
        enclosingClass,
        enclosingMethod,
        argIndex,
        enclosingReceiver,
        enclosingMethodName,
        lambdaParamIndex,
        declaredTypeText,
        docVersion);
  }
}
