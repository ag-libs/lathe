package io.github.aglibs.lathe.server.analysis.completion;

import java.util.List;

final class CompletionCandidateRanker {

  private CompletionCandidateRanker() {}

  static List<RankedCompletionCandidate> rank(
      final List<CompletionCandidate> candidates, final SemanticCompletionContext context) {
    return candidates.stream()
        .map(candidate -> new RankedCompletionCandidate(candidate, candidate.sortText(), false))
        .toList();
  }
}
