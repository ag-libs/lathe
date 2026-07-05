package io.github.aglibs.lathe.server.analysis.completion;

import java.util.Comparator;
import java.util.List;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

final class CompletionCandidateRanker {

  private CompletionCandidateRanker() {}

  static List<RankedCompletionCandidate> rank(
      final List<CompletionCandidate> candidates, final SemanticCompletionContext context) {
    if (context.expectedValue() instanceof ExpectedValue.NoSlot) {
      return List.of();
    }

    return candidates.stream()
        .filter(candidate -> valid(candidate, context))
        .map(
            candidate ->
                new RankedCompletionCandidate(candidate, sortText(candidate, context), false))
        .sorted(
            Comparator.comparing(
                RankedCompletionCandidate::sortText, Comparator.nullsFirst(String::compareTo)))
        .toList();
  }

  private static boolean valid(
      final CompletionCandidate candidate, final SemanticCompletionContext context) {
    if (!valueSensitiveContext(context)) {
      return true;
    }

    return !voidMethod(candidate) && compatibleKeyword(candidate, context);
  }

  private static String sortText(
      final CompletionCandidate candidate, final SemanticCompletionContext context) {
    if (candidate.kind() == CandidateKind.KEYWORD) {
      return keywordSortText(candidate, context);
    }

    if (context.staticMemberResultContext() != null) {
      final boolean matches =
          context.staticMemberResultContext().matches(candidate.valueType(), context.analysis());
      final String base = baseSortText(candidate);
      return "%d_%d_%s"
          .formatted(
              matches ? 0 : 1,
              kindPriority(candidate.kind()),
              base != null ? base : candidate.name());
    }

    if (!(context.expectedValue() instanceof ExpectedValue.Type(final TypeMirror type))) {
      return baseSortText(candidate);
    }

    final boolean matches = assignableToExpected(candidate.valueType(), type, context);
    final String base = baseSortText(candidate);
    return "%d_%d_%s"
        .formatted(
            matches ? 0 : 1,
            kindPriority(candidate.kind()),
            base != null ? base : candidate.name());
  }

  /**
   * Best-effort assignability tie-break for value-slot ranking. {@code JavacTypes.isAssignable}
   * throws {@link IllegalArgumentException} when handed a mirror whose kind is not a value type
   * (package, executable, module, …), which can reach here as a candidate's {@code valueType()} in
   * a typed slot such as an annotation value. Such a candidate is simply not assignable, so treat
   * any rejection as a non-match rather than letting it abort the whole completion.
   */
  private static boolean assignableToExpected(
      final TypeMirror valueType,
      final TypeMirror expected,
      final SemanticCompletionContext context) {
    if (valueType == null) {
      return false;
    }

    try {
      return context.analysis().types().isAssignable(valueType, expected);
    } catch (final IllegalArgumentException e) {
      return false;
    }
  }

  private static int kindPriority(final CandidateKind kind) {
    return switch (kind) {
      case LOCAL_VARIABLE -> 0;
      case FIELD -> 1;
      case METHOD -> 2;
      default -> 3;
    };
  }

  private static String baseSortText(final CompletionCandidate candidate) {
    if (candidate.sortText() != null) {
      return candidate.sortText();
    }

    if (objectMethod(candidate)) {
      return "9_%s".formatted(candidate.name());
    }

    return null;
  }

  private static String keywordSortText(
      final CompletionCandidate candidate, final SemanticCompletionContext context) {
    if (context.expectedValue() instanceof ExpectedValue.Type(final TypeMirror type)) {
      if (("true".equals(candidate.name()) || "false".equals(candidate.name()))
          && booleanCompatible(type, context)) {
        return "0_%s".formatted(candidate.name());
      }
    }
    if ("null".equals(candidate.name()) && context.inEqualityComparison()) {
      return "0_null";
    }
    if ("return".equals(candidate.name()) && context.inNonVoidMethod()) {
      return "0_return";
    }
    return candidate.sortText();
  }

  private static boolean valueSensitiveContext(final SemanticCompletionContext context) {
    return context.valueContext() || context.expectedValue() instanceof ExpectedValue.Type;
  }

  private static boolean compatibleKeyword(
      final CompletionCandidate candidate, final SemanticCompletionContext context) {
    if (candidate.kind() != CandidateKind.KEYWORD) {
      return true;
    }

    if (!(context.expectedValue() instanceof ExpectedValue.Type(final TypeMirror type))) {
      return true;
    }

    if (isThrowable(type, context)) {
      return true;
    }

    return switch (candidate.name()) {
      case "true", "false" -> booleanCompatible(type, context);
      case "null" -> !type.getKind().isPrimitive();
      default -> true;
    };
  }

  private static boolean isThrowable(
      final TypeMirror type, final SemanticCompletionContext context) {
    return context.analysis().types().asElement(type) instanceof final TypeElement typeElement
        && "java.lang.Throwable".equals(typeElement.getQualifiedName().toString());
  }

  private static boolean booleanCompatible(
      final TypeMirror type, final SemanticCompletionContext context) {
    if (type.getKind() == TypeKind.BOOLEAN) {
      return true;
    }

    return context.analysis().types().asElement(type) instanceof final TypeElement typeElement
        && "java.lang.Boolean".equals(typeElement.getQualifiedName().toString());
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
