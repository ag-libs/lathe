package io.github.aglibs.lathe.server.analysis.completion;

import static io.github.aglibs.lathe.server.analysis.completion.CursorFixture.cursor;
import static io.github.aglibs.lathe.server.analysis.completion.SentinelInjector.SENTINEL;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.analysis.completion.SentinelInjector.Context;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class SentinelInjectorTest {

  private static SentinelResult inject(final String markedSource) {
    final var c = cursor(markedSource);
    return new SentinelInjector(c.content()).inject(c.offset());
  }

  @Test
  void memberAccess_sameLine() {
    final var result = inject("list.sub§");
    assertThat(result.prefix()).isEqualTo("sub");
    assertThat(result.receiverText()).isEqualTo("list");
    assertThat(result.context()).isEqualTo(Context.STATEMENT);
    assertThat(result.injectedContent()).isEqualTo("list." + SENTINEL + ";");
  }

  @Test
  void memberAccess_multilineChain() {
    final var result =
        inject(
            """
        stream()
            .filter(x -> true)
            .map§""");
    assertThat(result.prefix()).isEqualTo("map");
    assertThat(result.receiverText()).isEqualTo("filter(x -> true)");
  }

  @Test
  void memberAccess_dotWithNoPrefix() {
    final var result = inject("list.§");
    assertThat(result.prefix()).isEmpty();
    assertThat(result.receiverText()).isEqualTo("list");
  }

  @Test
  void simpleName_noReceiver() {
    final var result = inject("sub§");
    assertThat(result.prefix()).isEqualTo("sub");
    assertThat(result.receiverText()).isNull();
    assertThat(result.injectedContent()).isEqualTo(SENTINEL + ";");
  }

  @Test
  void expressionContext_insideArgList() {
    final var result = inject("foo(list.sub§)");
    assertThat(result.context()).isEqualTo(Context.EXPRESSION);
    assertThat(result.injectedContent()).isEqualTo("foo(list." + SENTINEL + ")");
  }

  @Test
  void expressionContext_afterColon() {
    assertThat(inject("x ? a : list.sub§").context()).isEqualTo(Context.EXPRESSION);
  }

  @Test
  void tailPreserved_lambdaClosingParenSurvives() {
    final var result = inject("items.forEach(x -> x.get§);");
    assertThat(result.injectedContent()).isEqualTo("items.forEach(x -> x." + SENTINEL + ");");
  }

  @Test
  void unclosedBracketsBalanced() {
    final var result = inject("void m() { foo(bar.sub§");
    // cursor is inside foo( → EXPRESSION context, no semicolon; unclosed ( and { are balanced
    assertThat(result.injectedContent()).isEqualTo("void m() { foo(bar." + SENTINEL + ")}");
  }

  @Test
  void dotAtStartOfContent_receiverIsNull() {
    assertThat(inject(".sub§").receiverText()).isNull();
  }

  // ── correct behaviour not yet pinned ──────────────────────────────────────

  @Test
  void expressionContext_insideBrackets() {
    final var result = inject("arr[list.sub§");
    assertThat(result.context()).isEqualTo(Context.EXPRESSION);
    assertThat(result.receiverText()).isEqualTo("list");
  }

  @Test
  void blockLambdaBody_isStatementContext() {
    // '{' inside an open paren does not terminate the scan — stays STATEMENT
    final var result = inject("items.forEach(x -> { x.sub§");
    assertThat(result.context()).isEqualTo(Context.STATEMENT);
    assertThat(result.receiverText()).isEqualTo("x");
  }

  @Test
  void methodCallReceiver() {
    final var result = inject("getList().sub§");
    assertThat(result.receiverText()).isEqualTo("getList()");
  }

  @Test
  void forwardScan_stringContentWithBrackets_notCounted() {
    // '{' and '(' inside a string literal must not affect the unclosed counts
    final var result = inject("String s = \"{ ( }\"; list.sub§");
    assertThat(result.injectedContent()).isEqualTo("String s = \"{ ( }\"; list." + SENTINEL + ";");
  }

  @Test
  void forwardScan_lineComment_notCounted() {
    final var result = inject("// {\nlist.sub§");
    assertThat(result.injectedContent()).isEqualTo("// {\nlist." + SENTINEL + ";");
  }

  @Test
  void forwardScan_blockComment_notCounted() {
    // ';' stops backwardScan before it reaches the comment, so only forwardScan is under test
    final var result = inject("/* ( { */; list.sub§");
    assertThat(result.injectedContent()).isEqualTo("/* ( { */; list." + SENTINEL + ";");
  }

  @Test
  void forwardScan_textBlock_notCounted() {
    // embedded '"' inside a text block must not flip inString — the '{' is inside the block
    final var result = inject("String s = \"\"\"\n    foo \" bar {\n    \"\"\"; list.sub§");
    assertThat(result.injectedContent())
        .isEqualTo("String s = \"\"\"\n    foo \" bar {\n    \"\"\"; list." + SENTINEL + ";");
  }

  @Test
  void switchExpressionArm_isExpressionContext() {
    assertThat(inject("case 1 -> list.sub§").context()).isEqualTo(Context.EXPRESSION);
  }

  @Test
  void expressionLambda_withoutEnclosingParen_isExpressionContext() {
    // comparing( is the outer paren; backward scan reaches it before any '->'
    final var result = inject("Comparator.comparing(s ->\n    s.toStr§");
    assertThat(result.context()).isEqualTo(Context.EXPRESSION);
  }

  @Test
  void castReceiver_extractedCorrectly() {
    assertThat(inject("((String) obj).sub§").receiverText()).isEqualTo("((String) obj)");
  }

  @Test
  void forwardScan_escapeQuoteInString_notCounted() {
    // '\"' must not toggle inString — the '{' and '(' that follow are inside the string
    final var result = inject("String s = \"he\\\"lo { (\"; list.sub§");
    assertThat(result.injectedContent())
        .isEqualTo("String s = \"he\\\"lo { (\"; list." + SENTINEL + ";");
  }

  @Test
  void forwardScan_charLiteralBrace_notCounted() {
    final var result = inject("char c = '{'; list.sub§");
    assertThat(result.injectedContent()).isEqualTo("char c = '{'; list." + SENTINEL + ";");
  }

  @Test
  void backwardScan_blockComment_notConfusedByBrackets() {
    // the '(' is inside a comment — context should still be STATEMENT
    final var result = inject("/* ( */ list.sub§");
    assertThat(result.context()).isEqualTo(Context.STATEMENT);
  }

  @Test
  void methodCallTail_lambdaArg() {
    // cursor at end of method name when '(args)' already follows in the file
    final var result = inject("list.forEach§(x -> x)");
    assertThat(result.injectedContent()).isEqualTo("list." + SENTINEL + "(x -> x)");
  }

  @Test
  void methodCallTail_methodCallArg() {
    final var result = inject("annotations.addAll§(other.values())");
    assertThat(result.injectedContent()).isEqualTo("annotations." + SENTINEL + "(other.values())");
  }

  @Test
  void backwardScan_lineComment_notConfusedByBrackets() {
    // '(' is inside a // comment — context should be STATEMENT
    assertThat(inject("// (\nlist.sub§").context()).isEqualTo(Context.STATEMENT);
  }

  @Test
  void backwardScan_string_notConfusedByBrackets() {
    // unmatched '(' inside a string literal must not trigger EXPRESSION context
    assertThat(inject("\"(\" + list.sub§").context()).isEqualTo(Context.STATEMENT);
  }

  @Test
  void backwardScan_charLiteral_notConfusedByBrackets() {
    // '(' as a char literal must not trigger EXPRESSION context
    assertThat(inject("'(' + list.sub§").context()).isEqualTo(Context.STATEMENT);
  }

  @Test
  void typeParameterBound_isExpressionContext() {
    // '<' of the type parameter list must set EXPRESSION so no ';' is inserted inside '<>'
    assertThat(inject("class Foo<T extends Bar§>").context()).isEqualTo(Context.EXPRESSION);
  }

  @Disabled(
      """
      cursor at START of an existing identifier (empty prefix): the injector appends \
      the sentinel directly to the remaining identifier chars, producing a single token \
      like __LATHE_SENTINEL__RoleConfig that SentinelFinder cannot match. \
      Fix: forward-scan past identifier chars at cursorOffset before building the suffix.""")
  @Test
  void cursorAtTokenStart_sentinelNotConcatenatedWithSuffix() {
    // real case: class RoleValidator implements AbacValidator<RoleValidator.§RoleConfig>
    // probe places cursor at START of RoleConfig — prefix is empty, but suffix chars follow
    final var result = inject("list.§values()");
    assertThat(result.injectedContent()).isEqualTo("list." + SENTINEL + "()");
  }
}
