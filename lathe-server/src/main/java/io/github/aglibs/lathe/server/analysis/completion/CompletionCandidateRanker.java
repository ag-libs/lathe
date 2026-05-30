package io.github.aglibs.lathe.server.analysis.completion;

import java.util.List;
import javax.lang.model.type.TypeKind;

final class CompletionCandidateRanker {

  private CompletionCandidateRanker() {}

  static List<RankedCompletionCandidate> rank(
      final List<CompletionCandidate> candidates, final SemanticCompletionContext context) {
    return candidates.stream()
        .filter(candidate -> valid(candidate, context))
        .map(
            candidate ->
                new RankedCompletionCandidate(candidate, sortText(candidate, context), false))
        .toList();
  }

  private static boolean valid(
      final CompletionCandidate candidate, final SemanticCompletionContext context) {
    if (!valueSensitiveContext(candidate, context)) {
      return true;
    }

    return !objectMethod(candidate) && !voidMethod(candidate);
  }

  private static String sortText(
      final CompletionCandidate candidate, final SemanticCompletionContext context) {
    if (candidate.sortText() != null) {
      return candidate.sortText();
    }

    if (objectMethod(candidate)) {
      return "9_%s".formatted(candidate.name());
    }

    if (!(context.expectedValue() instanceof final ExpectedValue.Type expected)) {
      return null;
    }

    final boolean matches =
        candidate.valueType() != null
            && context.analysis().types().isAssignable(candidate.valueType(), expected.type());
    return "%d_%s".formatted(matches ? 0 : 1, candidate.name());
  }

  private static boolean valueSensitiveContext(
      final CompletionCandidate candidate, final SemanticCompletionContext context) {
    return context.valueContext()
        || context.expectedValue() instanceof ExpectedValue.Type
        || candidate.valueSensitive();
  }

  private static boolean objectMethod(final CompletionCandidate candidate) {
    return candidate.kind() == CandidateKind.METHOD
        && "java.lang.Object".equals(candidate.declaringType());
  }

  private static boolean voidMethod(final CompletionCandidate candidate) {
    return candidate.kind() == CandidateKind.METHOD
        && candidate.valueType() != null
        && candidate.valueType().getKind() == TypeKind.VOID;
  }
}
