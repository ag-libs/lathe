package io.github.aglibs.lathe.analysis.completion;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

record ParsedSentinel(
    boolean valid,
    String prefix,
    String receiverText,
    int receiverStartOffset,
    int receiverEndOffset,
    SentinelContext sentinelContext,
    String enclosingClass,
    String enclosingMethod,
    int argIndex,
    String enclosingReceiver,
    String enclosingMethodName,
    int lambdaParamIndex,
    String declaredTypeText,
    String declaredElementTypeName,
    String annotationTypeText,
    TypeReferenceRole typeReferenceRole,
    boolean enclosedByLoop,
    boolean enclosedBySwitchStatement,
    boolean enclosedBySwitchExpression,
    boolean inEqualityComparison,
    KeywordSlot keywordSlot,
    boolean catchParameter,
    boolean constantField,
    // Where the top-level class ends when the sentinel is a switch label's body, -1 otherwise: a
    // second injection that closes the switch at the label keeps it only where the class still ends
    // at the text's last brace (CQ-0079).
    int labelTopLevelEnd,
    // Whether the sentinel is itself a statement of a switch label's body (a case of a switch
    // statement, or a colon case): a statement position, whatever the text scan read after the
    // label's `->` or `:` (CQ-0082).
    boolean labelBodyStatement,
    // The type variables a type written at the cursor may name, innermost first; empty where no
    // type variable can stand (an expression, a supertype, after `new`).
    List<String> typeVariables,
    ModuleDirectiveKind directiveKeyword,
    int docVersion) {

  ParsedSentinel {
    ValidCheck.check()
        .notNull(prefix, "prefix")
        .nullOrNotEmpty(receiverText, "receiverText")
        .when(valid, v -> v.notNull(sentinelContext, "sentinelContext"))
        .notNull(typeReferenceRole, "typeReferenceRole")
        .notNull(keywordSlot, "keywordSlot")
        .notNull(typeVariables, "typeVariables")
        .when(
            !valid,
            v ->
                v.isNull(sentinelContext, "sentinelContext")
                    .isNull(enclosingClass, "enclosingClass")
                    .isNull(enclosingMethod, "enclosingMethod"))
        .validate();
    typeVariables = List.copyOf(typeVariables);
  }

  static ParsedSentinel invalid(
      final String prefix, final String receiverText, final int docVersion) {
    return new ParsedSentinel(
        false,
        prefix,
        receiverText,
        -1,
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
        null,
        TypeReferenceRole.ORDINARY,
        false,
        false,
        false,
        false,
        KeywordSlot.BLOCK_STATEMENT,
        false,
        false,
        -1,
        false,
        List.of(),
        null,
        docVersion);
  }

  static ParsedSentinel valid(
      final SentinelInjectionResult injected,
      final SentinelContext context,
      final int receiverStartOffset,
      final int receiverEndOffset,
      final ModuleDirectiveKind directiveKeyword,
      final int docVersion) {
    return valid(
        injected,
        context,
        receiverStartOffset,
        receiverEndOffset,
        null,
        null,
        -1,
        null,
        null,
        -1,
        null,
        null,
        null,
        TypeReferenceRole.ORDINARY,
        false,
        false,
        false,
        false,
        KeywordSlot.BLOCK_STATEMENT,
        false,
        false,
        -1,
        false,
        List.of(),
        directiveKeyword,
        docVersion);
  }

  static ParsedSentinel valid(
      final SentinelInjectionResult injected,
      final SentinelContext context,
      final int receiverStartOffset,
      final int receiverEndOffset,
      final String enclosingClass,
      final String enclosingMethod,
      final int argIndex,
      final String enclosingReceiver,
      final String enclosingMethodName,
      final int lambdaParamIndex,
      final String declaredTypeText,
      final String declaredElementTypeName,
      final String annotationTypeText,
      final TypeReferenceRole typeReferenceRole,
      final boolean enclosedByLoop,
      final boolean enclosedBySwitchStatement,
      final boolean enclosedBySwitchExpression,
      final boolean inEqualityComparison,
      final KeywordSlot keywordSlot,
      final boolean catchParameter,
      final boolean constantField,
      final int labelTopLevelEnd,
      final boolean labelBodyStatement,
      final List<String> typeVariables,
      final ModuleDirectiveKind directiveKeyword,
      final int docVersion) {
    return new ParsedSentinel(
        true,
        injected.prefix(),
        injected.receiverText(),
        receiverStartOffset,
        receiverEndOffset,
        context,
        enclosingClass,
        enclosingMethod,
        argIndex,
        enclosingReceiver,
        enclosingMethodName,
        lambdaParamIndex,
        declaredTypeText,
        declaredElementTypeName,
        annotationTypeText,
        typeReferenceRole,
        enclosedByLoop,
        enclosedBySwitchStatement,
        enclosedBySwitchExpression,
        inEqualityComparison,
        keywordSlot,
        catchParameter,
        constantField,
        labelTopLevelEnd,
        labelBodyStatement,
        typeVariables,
        directiveKeyword,
        docVersion);
  }
}
