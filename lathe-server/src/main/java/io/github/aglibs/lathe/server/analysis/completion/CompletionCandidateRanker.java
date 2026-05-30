package io.github.aglibs.lathe.server.analysis.completion;

import java.util.List;

final class CompletionCandidateRanker {

  private CompletionCandidateRanker() {}

  static List<RankedCompletionCandidate> rank(
      final List<CompletionCandidate> candidates, final SemanticCompletionContext context) {
    return candidates.stream()
        .map(
            candidate ->
                new RankedCompletionCandidate(candidate, sortText(candidate, context), false))
        .toList();
  }

  private static String sortText(
      final CompletionCandidate candidate, final SemanticCompletionContext context) {
    if (candidate.sortText() != null) {
      return candidate.sortText();
    }

    if (!(context.expectedValue() instanceof final ExpectedValue.Type expected)) {
      return null;
    }

    final boolean matches =
        candidate.valueType() != null
            && context.analysis().types().isAssignable(candidate.valueType(), expected.type());
    return "%d_%s".formatted(matches ? 0 : 1, candidate.name());
  }
}
