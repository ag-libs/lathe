package io.github.aglibs.lathe.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
  void keyword_requiresFollowingConstruct_insertsTrailingSpace() {
    // A keyword that must be followed by a further construct inserts a trailing space, so
    // the developer keeps typing the mandatory next token without pressing space first.
    final List<CompletionItem> classBody = fixture.complete("class Test { § }");
    assertThat(insertTextOf(classBody, "class")).isEqualTo("class ");
    assertThat(insertTextOf(classBody, "static")).isEqualTo("static ");
    assertThat(insertTextOf(classBody, "void")).isEqualTo("void ");

    final List<CompletionItem> methodBody = fixture.complete("class Test { void m() { § } }");
    assertThat(insertTextOf(methodBody, "return")).isEqualTo("return ");
    assertThat(insertTextOf(methodBody, "new")).isEqualTo("new ");
  }

  @Test
  void keyword_standaloneValue_insertsNoTrailingSpace() {
    // Value/standalone keywords can precede punctuation (`.`/`(`/`;`) and stay bare -- an
    // editor never wants `null ;`.
    final List<CompletionItem> items = fixture.complete("class Test { void m() { throw § } }");
    assertThat(insertTextOf(items, "null")).isEqualTo("null");
    assertThat(insertTextOf(items, "true")).isEqualTo("true");
    assertThat(insertTextOf(items, "false")).isEqualTo("false");
    assertThat(insertTextOf(items, "this")).isEqualTo("this");
    assertThat(insertTextOf(items, "super")).isEqualTo("super");
  }

  @Test
  void classBody_modifierPrefix_suggestsMatchingModifier() {
    final var items = labels(fixture.complete("class Test { pri§ }"));
    assertThat(items).contains("private");
    assertThat(items).doesNotContain("protected");
  }

  // A record's member start is parsed as a method stub (CQ-0065), which is no enclosing method.
  @Test
  void recordBody_modifierPrefix_suggestsMatchingModifier() {
    assertThat(labels(fixture.complete("record Test(int x) { pub§ }"))).contains("public");
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

  // CQ-0084: where only a value or an expression statement stands, the keywords a block statement
  // takes are not offered. The value keywords an expected type rules out (`true` for an `int`) are
  // not asserted: the ranker drops those on its own.
  @ParameterizedTest(name = "{0}")
  @MethodSource("switchExpressionArrowBodies")
  void keywords_switchExpressionArrowBody_valueKeywordsAndThrow(
      final String label, final String source) {
    final List<String> items = labels(fixture.complete(source));
    assertThat(items).as(label).contains("new", "this", "super", "throw", "switch");
    assertThat(items)
        .as(label)
        .doesNotContain(
            "return",
            "if",
            "for",
            "while",
            "do",
            "try",
            "synchronized",
            "var",
            "final",
            "assert",
            "int",
            "yield",
            "break");
  }

  static Stream<Arguments> switchExpressionArrowBodies() {
    return Stream.of(
        Arguments.of(
            "closed",
            """
            class Test {
                Object m(int x) {
                    Object r = switch (x) {
                        case 1 -> §
                        default -> 0;
                    };
                    return r;
                }
            }"""),
        Arguments.of(
            "open",
            """
            class Test {
                int next;
                Object m(int x) {
                    Object r = switch (x) {
                        case 1 -> §
                    next = 1;
                }
            }"""),
        Arguments.of(
            "defaultArrow",
            """
            class Test {
                Object m(int x) {
                    return switch (x) {
                        case 1 -> 1;
                        default -> §
                    };
                }
            }"""),
        Arguments.of(
            "textAfterTheCursor",
            """
            class Test {
                Object m(int x) {
                    return switch (x) {
                        case 1 -> §;
                        default -> 0;
                    };
                }
            }"""));
  }

  // javac rejects a `return` out of a switch expression.
  @Test
  void keywords_switchExpressionArrowBodyWithPrefix_noReturn() {
    final String source =
        """
        class Test {
            Object m(int x) {
                return switch (x) {
                    case 1 -> %s§
                    default -> 0;
                };
            }
        }""";
    assertThat(labels(fixture.complete(source.formatted("re")))).doesNotContain("return");
    assertThat(labels(fixture.complete(source.formatted("th")))).contains("this", "throw");
    assertThat(labels(fixture.complete(source.formatted("sw")))).contains("switch");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("yieldValues")
  void keywords_yieldValue_valueKeywordsOnly(final String label, final String source) {
    final List<String> items = labels(fixture.complete(source));
    assertThat(items).as(label).contains("new", "this", "super", "switch");
    assertThat(items)
        .as(label)
        .doesNotContain("return", "if", "for", "var", "assert", "yield", "throw", "break");
  }

  static Stream<Arguments> yieldValues() {
    return Stream.of(
        Arguments.of(
            "closed",
            """
            class Test {
                Object m(int x) {
                    return switch (x) {
                        case 1:
                            yield §;
                        default:
                            yield 0;
                    };
                }
            }"""),
        Arguments.of(
            "open",
            """
            class Test {
                int next;
                Object m(int x) {
                    Object r = switch (x) {
                        case 1:
                            yield §
                    next = 1;
                }
            }"""));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("arrayInitializerElements")
  void keywords_arrayInitializerElement_valueKeywordsOnly(final String label, final String source) {
    final List<String> items = labels(fixture.complete(source));
    assertThat(items).as(label).contains("new", "this", "super");
    assertThat(items)
        .as(label)
        .doesNotContain("return", "if", "for", "var", "assert", "throw", "switch", "int");
  }

  static Stream<Arguments> arrayInitializerElements() {
    return Stream.of(
        Arguments.of("local", "class Test { void m() { Object[] a = { § }; } }"),
        Arguments.of("secondElement", "class Test { void m() { Object[] a = { this, § }; } }"),
        Arguments.of("newArray", "class Test { void m() { Object[] a = new Object[] { § }; } }"),
        Arguments.of("field", "class Test { Object[] f = { § }; }"));
  }

  // javac accepts only an expression statement, a block or a `throw` as a switch rule's body.
  @ParameterizedTest(name = "{0}")
  @MethodSource("switchStatementArrowBodies")
  void keywords_switchStatementArrowBody_expressionStatementStartersAndThrow(
      final String label, final String source) {
    final List<String> items = labels(fixture.complete(source));
    assertThat(items).as(label).contains("new", "this", "super", "throw");
    assertThat(items)
        .as(label)
        .doesNotContain(
            "return", "if", "for", "while", "var", "assert", "break", "switch", "null", "true",
            "false", "int");
  }

  static Stream<Arguments> switchStatementArrowBodies() {
    return Stream.of(
        Arguments.of(
            "closed",
            """
            class Test {
                void m(int x) {
                    switch (x) {
                        case 1 -> §
                    }
                }
            }"""),
        Arguments.of(
            "open",
            """
            class Test {
                int next;
                void m(int x) {
                    switch (x) {
                        case 1 -> §
                    next = 1;
                }
            }"""));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("forUpdates")
  void keywords_forUpdate_expressionStatementStartersOnly(final String label, final String source) {
    final List<String> items = labels(fixture.complete(source));
    assertThat(items).as(label).contains("new", "this", "super");
    assertThat(items)
        .as(label)
        .doesNotContain(
            "break", "continue", "switch", "return", "if", "throw", "var", "assert", "null", "int");
  }

  static Stream<Arguments> forUpdates() {
    return Stream.of(
        Arguments.of("firstUpdate", "class Test { void m() { for (int i = 0; i < 3; §) { } } }"),
        Arguments.of(
            "secondUpdate", "class Test { void m() { for (int i = 0; i < 3; i++, §) { } } }"));
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
    assertThat(labels(fixture.complete("class Test { b§ foo() {} }"))).contains("boolean", "byte");
  }

  // ── argument position ─────────────────────────────────────────────────────────
}
