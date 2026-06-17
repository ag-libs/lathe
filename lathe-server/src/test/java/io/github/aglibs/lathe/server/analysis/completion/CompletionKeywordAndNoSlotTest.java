package io.github.aglibs.lathe.server.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.Test;

class CompletionKeywordAndNoSlotTest extends CompletionTestSupport {

  @Test
  void classBody_emptyDeclaration_suggestsMemberDeclarationStarters() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        private String existing;

                        §
                    }""")))
        .contains("private", "protected", "public", "static", "final", "class", "interface");
    assertThat(labels(fixture.complete("enum Outer { FIRST; § }")))
        .contains("class", "interface", "enum", "record");
  }

  @Test
  void classBody_modifierPrefix_suggestsMatchingModifier() {
    final var items = labels(fixture.complete("class Test { pri§ }"));
    assertThat(items).contains("private");
    assertThat(items).doesNotContain("protected");
  }

  @Test
  void classBody_afterModifier_suggestsTypesAndNestedDeclarations() {
    assertThat(labels(fixture.complete("class Test { private § }")))
        .contains("String", "class", "interface", "enum", "record");
  }

  @Test
  void classBody_afterPrivateFinal_suppressesInvalidModifiers() {
    final var items = labels(fixture.complete("class Test { private final § }"));

    assertThat(items).contains("String", "class", "interface", "enum", "record");
    assertThat(items)
        .doesNotContain(
            "public",
            "private",
            "protected",
            "static",
            "final",
            "abstract",
            "synchronized",
            "transient",
            "volatile");
  }

  // ── method body ───────────────────────────────────────────────────────────────

  @Test
  void methodBody_emptyStatement_suggestsStatementStarters() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        void run() {
                            §
                        }
                    }""")))
        .contains("return", "if", "for", "while", "switch", "try", "throw", "new");
  }

  @Test
  void keywords_throwPosition_expressionKeywordsOnly() {
    final List<String> items =
        labels(
            fixture.complete(
                """
                class Test {
                    void m() {
                        throw §
                    }
                }"""));
    assertThat(items).contains("new", "null", "true", "false", "this", "super");
    assertThat(items)
        .doesNotContain(
            "if",
            "for",
            "while",
            "do",
            "switch",
            "try",
            "throw",
            "assert",
            "break",
            "continue",
            "final",
            "synchronized",
            "var");
    final List<String> enumConstructorArgumentItems =
        labels(fixture.complete("enum Kind { FIRST(§); Kind(String value) {} }"));
    assertThat(enumConstructorArgumentItems).contains("new", "null");
    assertThat(enumConstructorArgumentItems)
        .doesNotContain("if", "for", "while", "switch", "return", "throw", "var", "true", "false");
  }

  @Test
  void keywords_returnPosition_expressionKeywordsOnly() {
    final List<String> items =
        labels(
            fixture.complete(
                """
                class Test {
                    String m() {
                        return §
                    }
                }"""));
    assertThat(items).contains("new", "null", "this", "super");
    assertThat(items)
        .doesNotContain(
            "if",
            "for",
            "while",
            "do",
            "switch",
            "try",
            "throw",
            "assert",
            "break",
            "continue",
            "final",
            "synchronized",
            "var");

    final var retItems =
        fixture.complete(
            """
            class Test {
                String m() {
                    §
                }
            }""");
    assertThat(labels(retItems)).contains("return");
    final var returnItem = itemLabeled(retItems, "return").orElseThrow();
    assertThat(returnItem.getSortText()).isEqualTo("0_return");
  }

  @Test
  void keywords_variableInitializer_expressionKeywordsOnly() {
    final List<String> items =
        labels(
            fixture.complete(
                """
                class Test {
                    void m() {
                        String s = §
                    }
                }"""));
    assertThat(items).contains("new", "null", "this", "super");
    assertThat(items)
        .doesNotContain(
            "if",
            "for",
            "while",
            "do",
            "switch",
            "try",
            "throw",
            "assert",
            "break",
            "continue",
            "final",
            "synchronized",
            "var",
            "true",
            "false");
  }

  @Test
  void keywords_variableInitializer_afterCompleteExpression_noStatementKeywords() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        String m() { return ""; }
                        void caller() {
                            String s = m()§;
                        }
                    }""")))
        .doesNotContain("if", "for", "while", "do", "switch", "try", "throw", "assert");
  }

  @Test
  void keywords_methodCallArgument_afterCompleteExpression_noStatementKeywords() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        static void consume(String s) {}
                        String m() { return ""; }
                        void caller() {
                            consume(m()§);
                        }
                    }""")))
        .doesNotContain("if", "for", "while", "do", "switch", "try", "throw", "assert");
  }

  @Test
  void keywords_bareImport_suggestsStaticAndTopLevelPackages() {
    final List<String> items = labels(fixture.complete("import §;\n\nclass Test {}"));
    assertThat(items).contains("static", "java");
    assertThat(items).doesNotContain("if", "for", "while", "new", "null", "return");
  }

  @Test
  void keywords_breakAndContinue_suppressedInBareMethodBody() {
    final List<String> items =
        labels(
            fixture.complete(
                """
                class Test {
                    void m() {
                        §
                    }
                }"""));
    assertThat(items).doesNotContain("break", "continue", "yield");
    assertThat(items).contains("return", "if", "for", "while", "switch", "try", "throw", "new");
  }

  @Test
  void keywords_breakAndContinue_offeredInsideLoop() {
    final List<String> items =
        labels(
            fixture.complete(
                """
                class Test {
                    void m() {
                        for (int i = 0; i < 10; i++) {
                            §
                        }
                    }
                }"""));
    assertThat(items).contains("break", "continue");
    assertThat(items).doesNotContain("yield");
  }

  @Test
  void keywords_break_offeredInsideSwitchStatement() {
    final List<String> items =
        labels(
            fixture.complete(
                """
                class Test {
                    void m(int x) {
                        switch (x) {
                            case 1: §
                        }
                    }
                }"""));
    assertThat(items).contains("break");
    assertThat(items).doesNotContain("continue", "yield");
  }

  @Test
  void keywords_yield_offeredInsideSwitchExpression() {
    final List<String> items =
        labels(
            fixture.complete(
                """
                class Test {
                    void m(int x) {
                        int r = switch (x) {
                            case 1 -> {
                                §
                            }
                            default -> 0;
                        };
                    }
                }"""));
    assertThat(items).contains("yield");
    assertThat(items).doesNotContain("break", "continue");
  }

  @Test
  void keywords_trueAndFalse_rankedFirstForBooleanExpectedType() {
    final List<CompletionItem> items =
        fixture.complete(
            """
            class Test {
                void m() {
                    boolean b = §
                }
            }""");
    assertThat(labels(items)).contains("true", "false");
    final var trueItem = itemLabeled(items, "true").orElseThrow();
    final var falseItem = itemLabeled(items, "false").orElseThrow();
    assertThat(trueItem.getSortText()).isEqualTo("0_true");
    assertThat(falseItem.getSortText()).isEqualTo("0_false");
  }

  @Test
  void keywords_null_rankedFirstInEqualityComparison() {
    final List<CompletionItem> items =
        fixture.complete(
            """
            class Test {
                void m(String s) {
                    if (s == §) {}
                }
            }""");
    assertThat(labels(items)).contains("null");
    final var nullItem = itemLabeled(items, "null").orElseThrow();
    assertThat(nullItem.getSortText()).isEqualTo("0_null");
  }

  @Test
  void declarationName_fieldNameSlot_suppressesAllCandidates() {
    assertThat(labels(fixture.complete("class Test { String S§; }"))).isEmpty();
    assertThat(labels(fixture.complete("class Test { String §; }"))).isEmpty();
  }

  @Test
  void declarationName_localVarNameSlot_suppressesAllCandidates() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        void m() {
                            String S§;
                        }
                    }""")))
        .isEmpty();
  }

  @Test
  void stringLiteral_noCompletions() {
    assertThat(fixture.complete("class Test { void m() { String s = \"hello§\"; } }")).isEmpty();
  }

  @Test
  void topLevel_expressionBeforeClass_returnsEmpty() {
    assertThat(fixture.complete("foo.§\nclass Test {}")).isEmpty();
  }

  @Test
  void bareDot_inMethodBody_returnsEmpty() {
    // right after semicolon (same line and next line) and at block start
    assertThat(
            fixture.complete(
                """
                class Test {
                    void m() {
                        System.out.println("hi");.§
                    }
                }"""))
        .isEmpty();
    assertThat(
            fixture.complete(
                """
                class Test {
                    void m() {
                        System.out.println("hi");
                        .§
                    }
                }"""))
        .isEmpty();
    assertThat(fixture.complete("class Test { void m() { .§ } }")).isEmpty();
  }

  @Test
  void topLevel_suggestsPackageImportAndClassKeywords() {
    assertThat(labels(fixture.complete("§\nclass Foo {}")))
        .contains(
            "package",
            "import",
            "class",
            "interface",
            "enum",
            "record",
            "public",
            "final",
            "abstract")
        .doesNotContain("if", "for", "while", "return", "new", "var", "null", "private", "void");
  }

  @Test
  void bareDot_inClassBody_returnsEmpty() {
    assertThat(fixture.complete("class Test { .§ }")).isEmpty();
  }

  // ── primitives ────────────────────────────────────────────────────────────────

  @Test
  void variableDeclaration_typeSlot_includesPrimitives() {
    final List<String> items =
        labels(
            fixture.complete(
                """
                class Test {
                    void m() {
                        i§
                    }
                }"""));
    assertThat(items).contains("int");
  }

  @Test
  void typeReference_inMethodBody_includesPrimitives() {
    final List<String> items =
        labels(
            fixture.complete(
                """
                class Test {
                    void m() {
                        Object x = (b§) null;
                    }
                }"""));
    assertThat(items).contains("boolean", "byte");
  }

  @Test
  void classBody_returnType_includesPrimitives() {
    assertThat(labels(fixture.complete("class Test { b§ foo() {} }")))
        .contains("boolean", "byte");
  }

  // ── argument position ─────────────────────────────────────────────────────────
}
