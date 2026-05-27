package io.github.aglibs.lathe.server.analysis.completion;

import java.util.List;
import java.util.stream.Stream;
import org.eclipse.lsp4j.CompletionItem;

/** Provides keyword completion items for a given sentinel context. */
final class KeywordProvider {

  // Value expressions valid anywhere an expression is expected
  private static final List<String> VALUE_EXPRESSIONS =
      List.of("new", "null", "true", "false", "this", "super");

  // Statement-level control flow
  private static final List<String> CONTROL_FLOW =
      List.of("if", "for", "while", "do", "switch", "try", "synchronized");

  // Statement terminators
  private static final List<String> TERMINATORS = List.of("return", "throw", "break", "continue");

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

  /**
   * Returns keyword completion items appropriate for the given context.
   *
   * @param injectorContext whether the cursor is inside an open-paren/bracket expression ({@link
   *     SentinelInjector.Context#EXPRESSION}) or at statement level ({@link
   *     SentinelInjector.Context#STATEMENT}). This is the primary discriminator between "any value
   *     expression" and "full statement keyword" sets, and is more reliable than inspecting the
   *     character immediately before the prefix: it covers all argument positions including
   *     second/third arguments separated by commas, not just positions immediately after {@code
   *     '('}.
   */
  static List<CompletionItem> suggest(
      final ParsedSentinel parsed,
      final String prefix,
      final SentinelInjector.Context injectorContext) {
    final var keywords = selectKeywords(parsed, injectorContext);
    if (keywords.isEmpty()) {
      return List.of();
    }

    return keywords.stream()
        .filter(kw -> kw.startsWith(prefix))
        .map(CompletionItemFactory::keyword)
        .toList();
  }

  private static List<String> selectKeywords(
      final ParsedSentinel parsed, final SentinelInjector.Context injectorContext) {
    final boolean inExpression = injectorContext == SentinelInjector.Context.EXPRESSION;
    return switch (parsed.sentinelContext()) {
      case SIMPLE_NAME ->
          // When the injector determined that the cursor is inside a parenthesised expression
          // (EXPRESSION context), statement-level keywords are syntactically invalid.  This
          // also handles the case where the sentinel replaces an existing identifier that is
          // the start of a member-select chain (e.g. super(§Foo.bar())) — javac classifies
          // that as SIMPLE_NAME, but the injector context correctly tells us we are inside
          // an argument list.
          inExpression ? VALUE_EXPRESSIONS : selectByScope(parsed);
      case TYPE_REFERENCE, VARIABLE_DECLARATION -> classBodyKeywordsIfApplicable(parsed);
      case ARGUMENT_POSITION, LAMBDA_BODY -> VALUE_EXPRESSIONS;
      case CONSTRUCTOR_CALL ->
          // EXPRESSION == cursor is in the argument list; STATEMENT == cursor is on the type name.
          inExpression ? VALUE_EXPRESSIONS : List.of();
      default -> List.of();
    };
  }

  private static List<String> selectByScope(final ParsedSentinel parsed) {
    if (parsed.enclosingMethod() != null) {
      return methodBodyKeywords();
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

  private static List<String> methodBodyKeywords() {
    return Stream.of(CONTROL_FLOW, TERMINATORS, VALUE_EXPRESSIONS, DECLARATION_STARTERS)
        .flatMap(List::stream)
        .toList();
  }

  private static List<String> classBodyKeywords() {
    return Stream.of(ACCESS_MODIFIERS, OTHER_MODIFIERS, TYPE_DECLARATIONS)
        .flatMap(List::stream)
        .toList();
  }
}
