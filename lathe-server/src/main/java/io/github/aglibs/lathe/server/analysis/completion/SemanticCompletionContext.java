package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import io.github.aglibs.validcheck.ValidCheck;

record SemanticCompletionContext(
    AttributedFileAnalysis analysis,
    ExpectedValue expectedValue,
    boolean valueContext,
    boolean inEqualityComparison,
    boolean inNonVoidMethod,
    CompletionLibraryRules.StaticMemberResultContext staticMemberResultContext) {

  SemanticCompletionContext {
    ValidCheck.check()
        .notNull(analysis, "analysis")
        .notNull(expectedValue, "expectedValue")
        .validate();
  }

  static SemanticCompletionContext blank(final AttributedFileAnalysis analysis) {
    return new SemanticCompletionContext(
        analysis, new ExpectedValue.Unknown(), false, false, false, null);
  }

  SemanticCompletionContext withExpectedValue(final ExpectedValue value) {
    return new SemanticCompletionContext(
        analysis,
        value,
        valueContext,
        inEqualityComparison,
        inNonVoidMethod,
        staticMemberResultContext);
  }

  SemanticCompletionContext asStatementContext() {
    return new SemanticCompletionContext(
        analysis,
        new ExpectedValue.Unknown(),
        false,
        inEqualityComparison,
        inNonVoidMethod,
        staticMemberResultContext);
  }

  static SemanticCompletionContext from(
      final CompletionSite site,
      final CompletionRequest request,
      final ParsedSentinel parsed,
      final AttributedFileAnalysis analysis) {
    final var expectedValue =
        TypeResolver.resolveExpectedValue(site, request.pos().getLine(), analysis);
    return new SemanticCompletionContext(
        analysis,
        expectedValue,
        site.injectorContext() == SentinelInjector.Context.EXPRESSION,
        parsed.inEqualityComparison(),
        TypeResolver.isNonVoidMethod(site.enclosingClass(), site.enclosingMethod(), analysis),
        TypeResolver.resolveStaticMemberResultContext(site, parsed, analysis));
  }
}
