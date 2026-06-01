package io.github.aglibs.lathe.server.analysis.completion;

import java.util.List;
import java.util.stream.Stream;

/** Provides keyword completion items for a given sentinel context. */
final class KeywordProvider {

  // Value expressions valid anywhere an expression is expected
  private static final List<String> VALUE_EXPRESSIONS =
      List.of("new", "null", "true", "false", "this", "super");

  // Statement-level control flow
  private static final List<String> CONTROL_FLOW =
      List.of("if", "for", "while", "do", "switch", "try", "synchronized");

  // Unconditional statement terminators
  private static final List<String> STATEMENT_TERMINATORS = List.of("return", "throw");

  // Local declaration starters
  private static final List<String> DECLARATION_STARTERS = List.of("var", "final", "assert");

  // Access modifiers valid at class-member level
  private static final List<String> ACCESS_MODIFIERS = List.of("public", "private", "protected");

  // Non-access modifiers valid at class-member level
  private static final List<String> OTHER_MODIFIERS =
      List.of("static", "final", "abstract", "synchronized", "transient", "volatile");

  // Type-declaration and return-type keywords valid at class-member level
  private static final List<String> TYPE_DECLARATIONS =
      List.of("class", "interface", "enum", "record", "void");

  // Keywords valid at the top level of a compilation unit (outside any class)
  private static final List<String> TOP_LEVEL =
      List.of(
          "package",
          "import",
          "public",
          "final",
          "abstract",
          "class",
          "interface",
          "enum",
          "record");

  private KeywordProvider() {}

  static List<CompletionCandidate> suggestCandidates(
      final ParsedSentinel parsed,
      final String prefix,
      final SentinelInjector.Context injectorContext) {
    final var keywords = selectKeywords(parsed, injectorContext);
    if (keywords.isEmpty()) {
      return List.of();
    }

    return keywords.stream()
        .filter(kw -> kw.startsWith(prefix))
        .map(KeywordProvider::keywordCandidate)
        .toList();
  }

  private static CompletionCandidate keywordCandidate(final String keyword) {
    return new CompletionCandidate(
        keyword,
        keyword,
        CandidateKind.KEYWORD,
        null,
        keyword,
        false,
        "8_%s".formatted(keyword),
        null,
        null,
        null);
  }

  private static List<String> selectKeywords(
      final ParsedSentinel parsed, final SentinelInjector.Context injectorContext) {
    return switch (parsed.sentinelContext()) {
      case SIMPLE_NAME -> parsed.inExpression() ? VALUE_EXPRESSIONS : selectByScope(parsed);
      case TYPE_REFERENCE, VARIABLE_DECLARATION -> classBodyKeywordsIfApplicable(parsed);
      case ARGUMENT_POSITION, LAMBDA_BODY -> VALUE_EXPRESSIONS;
      case CONSTRUCTOR_CALL ->
          // EXPRESSION == cursor is in the argument list; STATEMENT == cursor is on the type name.
          injectorContext == SentinelInjector.Context.EXPRESSION ? VALUE_EXPRESSIONS : List.of();
      default -> List.of();
    };
  }

  private static List<String> selectByScope(final ParsedSentinel parsed) {
    if (parsed.enclosingMethod() != null) {
      return methodBodyKeywords(parsed);
    }

    if (parsed.enclosingClass() != null) {
      return classBodyKeywords();
    }

    return TOP_LEVEL;
  }

  /** Returns class-body keywords only when at class scope; method-level type refs get nothing. */
  private static List<String> classBodyKeywordsIfApplicable(final ParsedSentinel parsed) {
    if (parsed.enclosingMethod() == null && parsed.enclosingClass() != null) {
      return classBodyKeywords();
    }

    return List.of();
  }

  private static List<String> methodBodyKeywords(final ParsedSentinel parsed) {
    return Stream.of(
            CONTROL_FLOW,
            STATEMENT_TERMINATORS,
            VALUE_EXPRESSIONS,
            DECLARATION_STARTERS,
            parsed.enclosedByLoop() || parsed.enclosedBySwitchStatement()
                ? List.of("break")
                : List.<String>of(),
            parsed.enclosedByLoop() ? List.of("continue") : List.<String>of(),
            parsed.enclosedBySwitchExpression() ? List.of("yield") : List.<String>of())
        .flatMap(List::stream)
        .toList();
  }

  private static List<String> classBodyKeywords() {
    return Stream.of(ACCESS_MODIFIERS, OTHER_MODIFIERS, TYPE_DECLARATIONS)
        .flatMap(List::stream)
        .toList();
  }
}
