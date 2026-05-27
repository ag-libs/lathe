package io.github.aglibs.lathe.server.analysis.completion;

import static io.github.aglibs.lathe.server.analysis.completion.CursorFixture.cursor;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.analysis.SourceParser;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class KeywordProviderTest {

  private static SourceParser sourceParser;
  private static SentinelParser sentinelParser;

  @BeforeAll
  static void setup() {
    sourceParser = new SourceParser();
    sentinelParser = new SentinelParser(sourceParser);
  }

  @AfterAll
  static void teardown() throws Exception {
    sourceParser.close();
  }

  private static java.util.List<String> keywords(final String markedSource) {
    final var c = cursor(markedSource);
    final var injected = new SentinelInjector(c.content()).inject(c.offset());
    final var parsed = sentinelParser.parse(injected, c.lspLine(), 0);
    return KeywordProvider.suggest(parsed, injected.prefix(), injected.context()).stream()
        .map(CompletionItem::getLabel)
        .toList();
  }

  // ── Method body ──────────────────────────────────────────────────────────

  @Test
  void methodBody_emptyPrefix_suggestsControlFlow() {
    final var kw =
        keywords(
            """
            class Test {
              void m() {
                §
              }
            }""");
    assertThat(kw).contains("if", "for", "while", "do", "switch", "try");
  }

  @Test
  void methodBody_emptyPrefix_suggestsTerminators() {
    final var kw =
        keywords(
            """
            class Test {
              void m() {
                §
              }
            }""");
    assertThat(kw).contains("return", "throw", "break", "continue");
  }

  @Test
  void methodBody_emptyPrefix_suggestsValueExpressions() {
    final var kw =
        keywords(
            """
            class Test {
              void m() {
                §
              }
            }""");
    assertThat(kw).contains("new", "null", "true", "false", "this");
  }

  @Test
  void methodBody_emptyPrefix_suggestsDeclarationStarters() {
    final var kw =
        keywords(
            """
            class Test {
              void m() {
                §
              }
            }""");
    assertThat(kw).contains("var", "final", "assert");
  }

  @Test
  void methodBody_emptyPrefix_noClassBodyKeywords() {
    final var kw =
        keywords(
            """
            class Test {
              void m() {
                §
              }
            }""");
    assertThat(kw).doesNotContain("public", "private", "protected", "static", "abstract");
  }

  @Test
  void methodBody_emptyPrefix_noElse() {
    // "else" is never a valid statement-start keyword
    final var kw =
        keywords(
            """
            class Test {
              void m() {
                §
              }
            }""");
    assertThat(kw).doesNotContain("else");
  }

  @Test
  void methodBody_prefix_filtersToMatchingKeywords() {
    final var kw =
        keywords(
            """
            class Test {
              void m() {
                re§
              }
            }""");
    assertThat(kw).contains("return");
    assertThat(kw).doesNotContain("if", "for", "while", "new", "var");
  }

  @Test
  void methodBody_prefix_noMatch_returnsEmpty() {
    final var kw =
        keywords(
            """
            class Test {
              void m() {
                xyz§
              }
            }""");
    assertThat(kw).isEmpty();
  }

  // ── Class body ───────────────────────────────────────────────────────────

  @Test
  void classBody_emptyPrefix_suggestsAccessModifiers() {
    final var kw =
        keywords(
            """
            class Test {
              §
            }""");
    assertThat(kw).contains("public", "private", "protected");
  }

  @Test
  void classBody_emptyPrefix_suggestsOtherModifiers() {
    final var kw =
        keywords(
            """
            class Test {
              §
            }""");
    assertThat(kw).contains("static", "final", "abstract");
  }

  @Test
  void classBody_emptyPrefix_suggestsTypeDeclarationKeywords() {
    final var kw =
        keywords(
            """
            class Test {
              §
            }""");
    assertThat(kw).contains("class", "interface", "enum", "record", "void");
  }

  @Test
  void classBody_emptyPrefix_noStatementKeywords() {
    final var kw =
        keywords(
            """
            class Test {
              §
            }""");
    assertThat(kw).doesNotContain("if", "for", "while", "return", "new", "var", "null");
  }

  @Test
  void classBody_prefix_filtersToMatchingKeywords() {
    final var kw =
        keywords(
            """
            class Test {
              pr§
            }""");
    assertThat(kw).contains("private", "protected");
    assertThat(kw).doesNotContain("public", "static", "final", "class");
  }

  // ── Argument position ────────────────────────────────────────────────────

  @Test
  void argumentPosition_emptyPrefix_suggestsValueExpressions() {
    final var kw =
        keywords(
            """
            class Test {
              void m() {
                System.out.println(§);
              }
            }""");
    assertThat(kw).contains("new", "null", "true", "false");
  }

  @Test
  void argumentPosition_emptyPrefix_noStatementKeywords() {
    final var kw =
        keywords(
            """
            class Test {
              void m() {
                System.out.println(§);
              }
            }""");
    assertThat(kw).doesNotContain("if", "for", "while", "return", "var", "assert");
  }

  // ── Constructor call ─────────────────────────────────────────────────────

  @Test
  void constructorCall_typeName_returnsEmpty() {
    // Typing the class name after "new" — no keywords belong here
    final var kw =
        keywords(
            """
            class Test {
              void m() {
                Object o = new §
              }
            }""");
    assertThat(kw).doesNotContain("if", "for", "return", "class", "public");
  }

  @Test
  void constructorCall_argument_suggestsValueExpressions() {
    // Inside constructor args: value expressions only
    final var kw =
        keywords(
            """
            class Test {
              void m() {
                Object o = new StringBuilder(§);
              }
            }""");
    assertThat(kw).contains("null", "true", "false", "new");
    assertThat(kw).doesNotContain("if", "for", "return", "var");
  }

  @Test
  void superConstructorCall_argument_noStatementKeywords() {
    // super(§) is a constructor invocation argument — only value expressions are valid
    final var kw =
        keywords(
            """
            class Child extends Object {
              Child() {
                super(§);
              }
            }""");
    assertThat(kw).doesNotContain("if", "for", "while", "return", "var", "class", "public");
  }

  @Test
  void superConstructorCall_existingArgument_noStatementKeywords() {
    // Cursor right after super( with an existing argument expression.
    // The sentinel replaces the first token and becomes a receiver in a member-select —
    // javac classifies as SIMPLE_NAME, but injectorContext==EXPRESSION suppresses
    // statement keywords correctly.
    final var kw =
        keywords(
            """
            class Child extends Object {
              Child() {
                super(§DbClientContext.builder().build());
              }
            }""");
    assertThat(kw).doesNotContain("if", "for", "while", "return", "var", "class", "public");
  }

  @Test
  void methodCall_secondArgument_existingExpr_noStatementKeywords() {
    // Cursor right after the comma separator — second arg position. charBeforePrefix is
    // a space, not '(', so the old charBeforePrefix==( heuristic would have missed this.
    // injectorContext==EXPRESSION (backward scan crosses ',' and hits '(') fires correctly.
    final var kw =
        keywords(
            """
            class Test {
              void m(String s) {
                System.out.printf("%s", §s.toUpperCase());
              }
            }""");
    assertThat(kw).doesNotContain("if", "for", "while", "return", "var", "class");
  }

  @Test
  void superConstructorCall_argument_suggestsValueExpressions() {
    final var kw =
        keywords(
            """
            class Child extends Object {
              Child() {
                super(§);
              }
            }""");
    assertThat(kw).contains("null", "true", "false", "new");
  }

  // ── Lambda body ──────────────────────────────────────────────────────────

  @Test
  void lambdaBody_emptyPrefix_suggestsValueExpressions() {
    final var kw =
        keywords(
            """
            class Test {
              void m() {
                java.util.function.Supplier<String> s = () -> §;
              }
            }""");
    assertThat(kw).contains("null", "true", "false", "new");
  }

  @Test
  void lambdaBody_emptyPrefix_noStatementKeywords() {
    final var kw =
        keywords(
            """
            class Test {
              void m() {
                java.util.function.Supplier<String> s = () -> §;
              }
            }""");
    assertThat(kw).doesNotContain("if", "for", "while", "return", "var");
  }

  // ── Member access ────────────────────────────────────────────────────────

  @Test
  void memberAccess_returnsEmpty() {
    final var kw =
        keywords(
            """
            class Test {
              void m() {
                System.out.§
              }
            }""");
    assertThat(kw).isEmpty();
  }

  // ── Import ───────────────────────────────────────────────────────────────

  @Test
  void importDeclaration_returnsEmpty() {
    final var kw =
        keywords(
            """
            import java.§;

            class Test {}""");
    assertThat(kw).isEmpty();
  }

  @Test
  void staticImportDeclaration_returnsEmpty() {
    final var kw =
        keywords(
            """
            import static java.util.Collections.§;

            class Test {}""");
    assertThat(kw).isEmpty();
  }

  // ── Top level (outside any class) ────────────────────────────────────────

  @Test
  void topLevel_emptyPrefix_suggestsPackageAndImport() {
    final var kw =
        keywords(
            """
            §
            class Foo {}""");
    assertThat(kw).contains("package", "import");
  }

  @Test
  void topLevel_emptyPrefix_suggestsTypeDeclarationKeywords() {
    final var kw =
        keywords(
            """
            §
            class Foo {}""");
    assertThat(kw).contains("class", "interface", "enum", "record");
  }

  @Test
  void topLevel_emptyPrefix_suggestsClassModifiers() {
    final var kw =
        keywords(
            """
            §
            class Foo {}""");
    assertThat(kw).contains("public", "final", "abstract");
  }

  @Test
  void topLevel_emptyPrefix_noStatementOrMemberKeywords() {
    final var kw =
        keywords(
            """
            §
            class Foo {}""");
    assertThat(kw)
        .doesNotContain("if", "for", "return", "new", "var", "null", "private", "static", "void");
  }

  @Test
  void topLevel_prefix_filtersToMatchingKeywords() {
    final var kw =
        keywords(
            """
            imp§
            class Foo {}""");
    assertThat(kw).containsExactly("import");
  }

  @Test
  void topLevel_packagePrefix_filtersToPackage() {
    final var kw =
        keywords(
            """
            pack§
            class Foo {}""");
    assertThat(kw).containsExactly("package");
  }
}
