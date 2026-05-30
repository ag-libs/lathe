package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import io.github.aglibs.validcheck.ValidCheck;

record SemanticCompletionContext(
    AttributedFileAnalysis analysis, ExpectedValue expectedValue, boolean valueContext) {

  SemanticCompletionContext {
    ValidCheck.check()
        .notNull(analysis, "analysis")
        .notNull(expectedValue, "expectedValue")
        .validate();
  }

  static SemanticCompletionContext from(
      final CompletionSite site,
      final CompletionRequest request,
      final AttributedFileAnalysis analysis) {
    final var expectedValue =
        TypeResolver.resolveExpectedValue(site, request.pos().getLine(), analysis);
    return new SemanticCompletionContext(
        analysis, expectedValue, site.injectorContext() == SentinelInjector.Context.EXPRESSION);
  }
}
