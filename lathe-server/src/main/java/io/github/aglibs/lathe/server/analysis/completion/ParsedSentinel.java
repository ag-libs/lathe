package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.validcheck.ValidCheck;

record ParsedSentinel(
    boolean valid,
    String prefix,
    String receiverText,
    int receiverEndOffset,
    SentinelContext sentinelContext,
    String enclosingClass,
    String enclosingMethod,
    int argIndex,
    String enclosingReceiver,
    String enclosingMethodName,
    int lambdaParamIndex,
    String declaredTypeText,
    String annotationTypeText,
    TypeReferenceRole typeReferenceRole,
    boolean enclosedByLoop,
    boolean enclosedBySwitchStatement,
    boolean enclosedBySwitchExpression,
    boolean inEqualityComparison,
    boolean inExpression,
    int docVersion) {

  ParsedSentinel {
    ValidCheck.check()
        .notNull(prefix, "prefix")
        .nullOrNotEmpty(receiverText, "receiverText")
        .when(valid, v -> v.notNull(sentinelContext, "sentinelContext"))
        .notNull(typeReferenceRole, "typeReferenceRole")
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
        false,
        prefix,
        receiverText,
        -1,
        null,
        null,
        null,
        -1,
        null,
        null,
        -1,
        null,
        null,
        TypeReferenceRole.ORDINARY,
        false,
        false,
        false,
        false,
        false,
        docVersion);
  }

  static ParsedSentinel valid(
      final SentinelResult injected,
      final SentinelContext context,
      final int receiverEndOffset,
      final int docVersion) {
    return valid(
        injected,
        context,
        receiverEndOffset,
        null,
        null,
        -1,
        null,
        null,
        -1,
        null,
        null,
        TypeReferenceRole.ORDINARY,
        false,
        false,
        false,
        false,
        false,
        docVersion);
  }

  static ParsedSentinel valid(
      final SentinelResult injected,
      final SentinelContext context,
      final int receiverEndOffset,
      final String enclosingClass,
      final String enclosingMethod,
      final int argIndex,
      final String enclosingReceiver,
      final String enclosingMethodName,
      final int lambdaParamIndex,
      final String declaredTypeText,
      final String annotationTypeText,
      final TypeReferenceRole typeReferenceRole,
      final boolean enclosedByLoop,
      final boolean enclosedBySwitchStatement,
      final boolean enclosedBySwitchExpression,
      final boolean inEqualityComparison,
      final boolean inExpression,
      final int docVersion) {
    return new ParsedSentinel(
        true,
        injected.prefix(),
        injected.receiverText(),
        receiverEndOffset,
        context,
        enclosingClass,
        enclosingMethod,
        argIndex,
        enclosingReceiver,
        enclosingMethodName,
        lambdaParamIndex,
        declaredTypeText,
        annotationTypeText,
        typeReferenceRole,
        enclosedByLoop,
        enclosedBySwitchStatement,
        enclosedBySwitchExpression,
        inEqualityComparison,
        inExpression,
        docVersion);
  }
}
