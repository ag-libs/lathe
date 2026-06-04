package io.github.aglibs.lathe.server.analysis.completion;

import static io.github.aglibs.lathe.server.analysis.completion.CompletionResultAssert.assertThatCompletion;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.typeindex.TypeKind;
import io.github.aglibs.lathe.server.TestCompiler;
import io.github.aglibs.lathe.server.analysis.WorkspaceTypeIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class CompletionEngineTest {

  @TempDir static Path sharedTmp;
  @TempDir Path tmp;

  private static CompletionFixture fixture;
  private CompletionFixture localFixture;

  @BeforeAll
  static void setup() throws IOException {
    fixture =
        new CompletionFixture(
            CompletionFixture.typeIndex(
                sharedTmp.resolve("index.json"),
                CompletionFixture.typeEntry("ArrayDeque", "java.util.ArrayDeque", TypeKind.CLASS),
                CompletionFixture.typeEntry(
                    "AbstractList", "java.util.AbstractList", TypeKind.CLASS),
                CompletionFixture.typeEntry("Integer", "java.lang.Integer", TypeKind.CLASS),
                CompletionFixture.typeEntry("Runnable", "java.lang.Runnable", TypeKind.INTERFACE),
                CompletionFixture.typeEntry(
                    "StringBuilder", "java.lang.StringBuilder", TypeKind.CLASS),
                CompletionFixture.typeEntry("String", "java.lang.String", TypeKind.CLASS),
                CompletionFixture.typeEntry(
                    "TimeUnit", "java.util.concurrent.TimeUnit", TypeKind.ENUM)));
  }

  @AfterAll
  static void teardown() {
    fixture.close();
  }

  @AfterEach
  void closeLocalFixture() {
    if (localFixture != null) {
      localFixture.close();
      localFixture = null;
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────────────

  private static List<String> labels(final List<CompletionItem> items) {
    return items.stream().map(CompletionItem::getLabel).toList();
  }

  private static Optional<CompletionItem> itemLabeled(
      final List<CompletionItem> items, final String label) {
    return items.stream().filter(i -> label.equals(i.getLabel())).findFirst();
  }

  private static Optional<CompletionItem> itemWithFilterText(
      final List<CompletionItem> items, final String text) {
    return items.stream().filter(i -> text.equals(i.getFilterText())).findFirst();
  }

  // ── member access ────────────────────────────────────────────────────────────

  @Test
  void memberAccess_instanceMethod_prefixFiltered() {
    final var items =
        fixture.complete(
            """
            class Test {
                void m(java.util.ArrayList<String> list) {
                    list.sub§
                }
            }""");
    assertThat(labels(items)).anyMatch(l -> l.startsWith("subList"));
    assertThat(items).noneMatch(i -> i.getLabel().startsWith("size"));
  }

  @Test
  void memberAccess_thisReceiver_fieldIncluded() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        String name = "x";
                        void m() {
                            this.na§
                        }
                    }""")))
        .contains("name");
  }

  @Test
  void memberAccess_staticFqnReceiver_staticMethodIncluded() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        void m() {
                            java.util.Collections.empty§
                        }
                    }""")))
        .anyMatch(l -> l.startsWith("emptyList"));
  }

  @Test
  void memberAccess_instanceReceiver_staticMethodsExcluded() {
    final var items =
        fixture.complete(
            """
            class Test {
                void m() {
                    java.util.stream.Stream.of("").§
                }
            }""");
    assertThat(labels(items)).anyMatch(l -> l.startsWith("filter"));
    assertThat(items)
        .noneMatch(
            i ->
                i.getLabel().startsWith("of(")
                    || i.getLabel().startsWith("empty(")
                    || i.getLabel().startsWith("builder("));
  }

  @Test
  void memberAccess_privateMember_visibilityRules() {
    // private members: accessible through this inside the declaring class, not through other type
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Other {
                        private String secret = "x";
                        public String visible = "y";
                    }

                    class Test {
                        void m(Other other) {
                            other.§
                        }
                    }""")))
        .contains("visible")
        .doesNotContain("secret");
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        private String secret = "x";

                        void m() {
                            this.se§
                        }
                    }""")))
        .contains("secret");
  }

  @Test
  void memberAccess_complexReceiver_returnTypeResolved() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        java.util.List<String> getList() { return null; }
                        void m() {
                            getList().sub§
                        }
                    }""")))
        .anyMatch(l -> l.startsWith("subList"));
  }

  @Test
  void memberAccess_receiverInArgument_completionsReturned() {
    // receiver.§ inside method call arg and constructor call arg
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        static void consume(String s) {}
                        void m() {
                            String hello = "x";
                            consume(hello.§);
                        }
                    }""")))
        .anyMatch(l -> l.startsWith("toLowerCase"));
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        void m() {
                            String hello = "x";
                            new StringBuilder(hello.§);
                        }
                    }""")))
        .anyMatch(l -> l.startsWith("toLowerCase"));
  }

  @Test
  void memberAccess_objectMethodsIncludedAndRankLast() {
    final var items =
        fixture.complete(
            """
            class Test {
                void m(java.util.ArrayList<String> list) {
                    list.§
                }
            }""");
    final var sizeSort =
        itemLabeled(items, "size()").map(CompletionItem::getSortText).orElseThrow();
    assertThat(items).anyMatch(i -> i.getLabel().startsWith("wait("));
    assertThat(items).anyMatch(i -> i.getLabel().equals("notify()"));
    assertThat(items).anyMatch(i -> i.getLabel().equals("notifyAll()"));
    items.stream()
        .filter(
            i ->
                i.getLabel().startsWith("wait(")
                    || i.getLabel().equals("notify()")
                    || i.getLabel().equals("notifyAll()"))
        .forEach(i -> assertThat(i.getSortText()).isGreaterThan(sizeSort));
  }

  @Test
  void memberAccess_typeArgResolved_notRawTypeVar() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        void m(java.util.List<String> list) {
                            list.add§
                        }
                    }""")))
        .contains("add(String)")
        .doesNotContain("add(E)");
  }

  @Test
  void memberAccess_samePackageType_staticMembersReturned() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    package com.example;
                    class Helper {
                        static String greet() { return "hi"; }
                    }
                    class Test {
                        void m() {
                            Helper.§
                        }
                    }""")))
        .anyMatch(l -> l.startsWith("greet"));
  }

  @Test
  void memberAccess_starImport_staticMembersReturned() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    import java.util.*;
                    class Test {
                        void m() {
                            Collections.§
                        }
                    }""")))
        .anyMatch(l -> l.startsWith("emptyList"));
  }

  @Test
  void memberAccess_staticEnumReceiver_enumConstantsReturned() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    enum Kind {
                        FIRST,
                        SECOND
                    }

                    class Test {
                        void m() {
                            Kind.§
                        }
                    }""")))
        .contains("FIRST", "SECOND");
    assertThat(
            labels(fixture.complete("class Test { void m() { java.util.concurrent.TimeUnit.§ } }")))
        .contains("SECONDS");
    assertThat(
            labels(
                fixture.complete(
                    "class Test { void m() { java.util.concurrent.TimeUnit.SECONDS.to§ } }")))
        .anyMatch(l -> l.startsWith("toMillis"));
  }

  @Test
  void memberAccess_newClassReceiver_methodsReturned() {
    assertThat(labels(fixture.complete("class Test { void m() { new StringBuilder().ap§ } }")))
        .anyMatch(l -> l.startsWith("append"));
  }

  @Test
  void memberAccess_instanceFieldChain_typeResolved() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        String name = "x";
                        void m() {
                            this.name.toL§
                        }
                    }""")))
        .anyMatch(l -> l.startsWith("toLowerCase"));
  }

  @Test
  void memberAccess_staticFieldChain_typeResolved() {
    assertThat(labels(fixture.complete("class Test { void m() { System.out.print§ } }")))
        .anyMatch(l -> l.startsWith("print"));
  }

  @Test
  void memberAccess_arrayElementReceiver_typeResolved() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        void m(String[] arr) {
                            arr[0].toL§
                        }
                    }""")))
        .anyMatch(l -> l.startsWith("toLowerCase"));
  }

  @Test
  void memberAccess_castReceiver_typeResolved() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        void m(Object obj) {
                            ((String) obj).toL§
                        }
                    }""")))
        .anyMatch(l -> l.startsWith("toLowerCase"));
  }

  @Test
  void memberAccess_methodParamSameLine_typeResolved() {
    assertThat(labels(fixture.complete("class Test { void m(String s) { s.to§ } }")))
        .anyMatch(l -> l.startsWith("toLowerCase"));
  }

  @Test
  void memberAccess_stringLiteralReceiver_stringMethodsReturned() {
    assertThat(labels(fixture.complete("class Test { void m() { \"hello\".to§ } }")))
        .anyMatch(l -> l.startsWith("toLowerCase"));
  }

  @Test
  void memberAccess_midChainFollowedByNextCall_returnTypeResolved() {
    // Standalone statement: backward scan hits { → STATEMENT context → semicolon injected.
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        void m() {
                            new StringBuilder()
                                    .append("a")
                                    .§
                                    .append("b");
                        }
                    }""")))
        .anyMatch(l -> l.startsWith("append"));
    // Chain inside a method call argument: backward scan hits ( → EXPRESSION context → no
    // semicolon. Sentinel becomes the receiver of the following .append(), which was wrongly
    // classified as SIMPLE_NAME instead of MEMBER_ACCESS.
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        static void consume(Object o) {}
                        void m() {
                            consume(new StringBuilder()
                                    .append("a")
                                    .§
                                    .append("b"));
                        }
                    }""")))
        .anyMatch(l -> l.startsWith("append"));
  }

  // ── FQN / package navigation ──────────────────────────────────────────────────

  @Test
  void fqnNavigation_topLevelPackage_suggestsSubPackages() {
    final List<String> items =
        labels(
            fixture.complete(
                """
                class Test {
                    void m() {
                        java.§
                    }
                }"""));
    assertThat(items).contains("util", "lang");
    assertThat(items).doesNotContain("ArrayList", "String", "if", "for");
  }

  @Test
  void fqnNavigation_nestedPackage_suggestsTypesAndSubPackages() {
    final List<String> items =
        labels(
            fixture.complete(
                """
                class Test {
                    void m() {
                        java.util.§
                    }
                }"""));
    assertThat(items).contains("ArrayDeque", "AbstractList", "concurrent");
    assertThat(items).doesNotContain("if", "for", "TimeUnit");
  }

  @Test
  void fqnNavigation_deepPackage_suggestsTypes() {
    final List<String> items =
        labels(
            fixture.complete(
                """
                class Test {
                    void m() {
                        java.util.concurrent.§
                    }
                }"""));
    assertThat(items).contains("TimeUnit");
    assertThat(items).doesNotContain("ArrayDeque", "AbstractList");
  }

  // ── import declarations ───────────────────────────────────────────────────────

  @Test
  void importDeclaration_nonStatic_suggestsSegmentsAndTypes() {
    // prefix navigation
    assertThat(labels(fixture.complete("import java.ut§;\n\nclass Test {}"))).contains("util");
    assertThat(labels(fixture.complete("import java.§;\n\nclass Test {}"))).contains("util");
    // nested package: types and sub-packages appear; static members do not
    final var afterUtil = fixture.complete("import java.util.§;\n\nclass Test {}");
    assertThat(labels(afterUtil)).contains("Collections", "concurrent");
    assertThat(afterUtil).noneMatch(i -> i.getLabel().equals("emptyList"));
    // type segment
    assertThat(labels(fixture.complete("import java.util.Col§;\n\nclass Test {}")))
        .contains("Collections");
    // non-static import path must not suggest static members
    assertThat(fixture.complete("import java.util.Collections.empty§;\n\nclass Test {}"))
        .noneMatch(i -> i.getLabel().startsWith("emptyList"));
    // non-matching prefix: unrelated types must not appear
    assertThat(fixture.complete("import java.util.Xyz§;\n\nclass Test {}"))
        .noneMatch(i -> i.getLabel().equals("ArrayList"));
    // deep-nested sub-package must not appear as immediate child of java.util
    assertThat(labels(fixture.complete("import java.util.§;\n\nclass Test {}")))
        .doesNotContain("atomic");
    // text edit includes trailing semicolon (source has no trailing ';' so engine adds one)
    final var mapItem = itemLabeled(fixture.complete("import java.util.§\n\nclass Test {}"), "Map");
    assertThat(mapItem).isPresent();
    assertThat(mapItem.get().getTextEdit().getLeft().getNewText()).isEqualTo("Map;");
  }

  @Test
  void importDeclaration_staticImport_suggestsSegmentsAndBareNames() {
    // prefix navigation
    assertThat(labels(fixture.complete("import static java.§;\n\nclass Test {}"))).contains("util");
    // package level: sub-types and packages, no static members
    final var afterUtil = fixture.complete("import static java.util.§;\n\nclass Test {}");
    assertThat(labels(afterUtil)).contains("Collections", "concurrent");
    assertThat(afterUtil).noneMatch(i -> i.getLabel().equals("emptyList"));
    // type level: static members appear
    assertThat(
            labels(
                fixture.complete("import static java.util.Collections.empty§;\n\nclass Test {}")))
        .anyMatch(l -> l.startsWith("emptyList"));
    // text edit is bare name + semicolon, not a snippet
    final var equalsItem =
        fixture.complete("import static java.util.Objects.§\n\nclass Test {}").stream()
            .filter(i -> i.getLabel().startsWith("equals"))
            .findFirst();
    assertThat(equalsItem).isPresent();
    assertThat(equalsItem.get().getTextEdit().getLeft().getNewText()).isEqualTo("equals;");
    assertThat(
            labels(
                fixture.complete("import static java.util.concurrent.TimeUnit.§\nclass Test {}")))
        .contains("SECONDS");
  }

  // ── class body ───────────────────────────────────────────────────────────────

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

    final var returnItem =
        itemLabeled(
            fixture.complete(
                """
                class Test {
                    String m() {
                        §
                    }
                }"""),
            "return");
    assertThat(returnItem).isPresent();
    assertThat(returnItem.get().getSortText()).isEqualTo("0_return");
  }

  @Test
  void variableInitializer_nonAssignableLocal_excluded() {
    final var items =
        labels(
            fixture.complete(
                """
                class Test {
                    void m() {
                        StringBuilder sb = new StringBuilder();
                        String text = "";
                        String result = §
                    }
                }"""));
    assertThat(items).contains("text").doesNotContain("sb");
  }

  @Test
  void assignment_nonAssignableType_excluded() {
    final var items =
        labels(
            fixture.complete(
                """
                class Test {
                    static class Foo {}
                    Foo getFoo() { return null; }
                    boolean isReady() { return true; }
                    void m() {
                        Foo x = new Foo();
                        x = §
                    }
                }"""));
    assertThat(items).contains("getFoo()");
    assertThat(items).doesNotContain("isReady()", "true", "false");
  }

  @Test
  void returnPosition_nonAssignableLocal_excluded() {
    final var items =
        labels(
            fixture.complete(
                """
                class Test {
                    StringBuilder sb = new StringBuilder();
                    String text = "";
                    String getValue() {
                        return §;
                    }
                }"""));
    assertThat(items).contains("text").doesNotContain("sb");
  }

  @Test
  void variableInitializer_userDefinedReferenceType_nonAssignableCandidatesExcluded() {
    final var items =
        labels(
            fixture.complete(
                """
                class Test {
                    static class Foo {}
                    Foo getFoo() { return null; }
                    void doSomething() {}
                    boolean isReady() { return true; }
                    String getString() { return ""; }
                    StringBuilder getSb() { return null; }
                    void m() {
                        Foo x = §
                    }
                }"""));
    assertThat(items).contains("getFoo()");
    assertThat(items)
        .doesNotContain("doSomething()", "isReady()", "true", "false", "getString()", "getSb()");
  }

  @Test
  void variableInitializer_unresolvedReferenceType_voidMethodAndBooleansExcluded() {
    final var items =
        labels(
            fixture.complete(
                """
                class Test {
                    void doSomething() {}
                    boolean isReady() { return true; }
                    void m() {
                        UnknownFoo x = §
                    }
                }"""));
    assertThat(items).doesNotContain("doSomething()", "isReady()", "true", "false");
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
    final var trueItem = itemLabeled(items, "true");
    final var falseItem = itemLabeled(items, "false");
    assertThat(trueItem).isPresent();
    assertThat(falseItem).isPresent();
    assertThat(trueItem.get().getSortText()).isEqualTo("0_true");
    assertThat(falseItem.get().getSortText()).isEqualTo("0_false");
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
    final var nullItem = itemLabeled(items, "null");
    assertThat(nullItem).isPresent();
    assertThat(nullItem.get().getSortText()).isEqualTo("0_null");
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
  void methodBody_afterNew_suggestsConstructibleTypes() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        void run() {
                            Object value = new Str§
                        }
                    }""")))
        .contains("StringBuilder");
    assertThat(labels(fixture.complete("class Test { void m() { Object value = new Mat§ } }")))
        .doesNotContain("Math");
    assertThat(labels(fixture.complete("class Test { void m() { Object value = new Runn§ } }")))
        .doesNotContain("Runnable");
    assertThat(labels(fixture.complete("class Test { void m() { Object value = new TimeU§ } }")))
        .doesNotContain("TimeUnit");
  }

  @Test
  void staticMethodBody_simpleName_doesNotSuggestInstanceMembers() {
    final var items =
        labels(
            fixture.complete(
                """
                class Test {
                    String instanceValue;
                    static String staticValue;

                    static void run() {
                        §
                    }
                }"""));
    assertThat(items).contains("staticValue");
    assertThat(items).doesNotContain("instanceValue");
  }

  // ── type references ───────────────────────────────────────────────────────────

  @Test
  void typeReference_dottedOuterClass_innerTypeSuggested() {
    assertThat(labels(fixture.complete("class Test { void m(java.util.Map.En§ entry) {} }")))
        .anyMatch(l -> l.startsWith("Entry"));
  }

  @Test
  void typeReference_simpleNamePrefixes_suggestMatchingTypes() {
    // method param, generic arg with non-empty and empty prefix
    assertThat(labels(fixture.complete("class Test { void m(Str§ param) {} }"))).contains("String");
    assertThat(labels(fixture.complete("class Test { void m() { java.util.List<Str§> list; } }")))
        .contains("String");
    assertThat(labels(fixture.complete("class Test { void m() { java.util.List<§> list; } }")))
        .contains("String", "Integer")
        .doesNotContain("if", "return", "new", "var", "class", "interface");
    assertThat(labels(fixture.complete("class Test { void m() throws RuntimeEx§ {} }")))
        .contains("RuntimeException");
    assertThat(labels(fixture.complete("class Test { void m() throws Str§ {} }")))
        .doesNotContain("String");
    assertThat(labels(fixture.complete("@Over§ class Test {}"))).contains("Override");
    assertThat(labels(fixture.complete("@Str§ class Test {}"))).doesNotContain("String");
  }

  @Test
  void annotationArgument_emptyList_suggestsElementNames() {
    assertThat(labels(fixture.complete("@Deprecated(§) class Test {}")))
        .contains("since", "forRemoval")
        .doesNotContain("Override", "SuppressWarnings", "true", "false", "class");
    assertThat(labels(fixture.complete("@Deprecated(si§) class Test {}")))
        .contains("since")
        .doesNotContain("forRemoval");
    assertThat(labels(fixture.complete("@Deprecated(since = §) class Test {}")))
        .doesNotContain("since", "forRemoval");
    assertThat(labels(fixture.complete("class Test { @Deprecated(§) @Override void foo() {} }")))
        .contains("since", "forRemoval")
        .doesNotContain("Override", "SuppressWarnings", "true", "false", "class");
    assertThat(labels(fixture.complete("class Test { @Deprecated(§ @Override void foo() {} }")))
        .contains("since", "forRemoval")
        .doesNotContain("Override", "SuppressWarnings", "true", "false", "class");
    assertThat(labels(fixture.complete("class Test { @Deprecated(si§ @Override void foo() {} }")))
        .contains("since")
        .doesNotContain("forRemoval");
  }

  @Test
  void annotationArgumentValue_booleanElement_offersTrueAndFalse() {
    final List<String> items =
        labels(fixture.complete("@Deprecated(forRemoval = §) class Test {}"));
    assertThat(items).contains("true", "false");
    assertThat(items).doesNotContain("since", "forRemoval", "if", "for", "class");
  }

  @Test
  void annotationArgumentValue_stringElement_offersNullNotBooleans() {
    final List<String> items = labels(fixture.complete("@Deprecated(since = §) class Test {}"));
    assertThat(items).containsOnly("null");
  }

  @Test
  void annotationArgument_namedElementPrefix_beforeEquals_suggestsElementName() {
    // cursor is in the name slot with "= value" already written: va§ = ""
    assertThat(labels(fixture.complete("@SuppressWarnings(va§ = \"\") class Test {}")))
        .contains("value")
        .doesNotContain("String", "class", "true", "false");
  }

  @Test
  void annotationDeclarationBody_elementReturnType_suggestsType() {
    assertThat(labels(fixture.complete("@interface Marker { Str§ value(); }")))
        .contains("String")
        .doesNotContain("true", "false");
  }

  @Test
  void classHeader_suggestsSuperAndInterfaceTypes() {
    assertThat(labels(fixture.complete("class Test extends AbstractL§ {}")))
        .contains("AbstractList");
    assertThat(labels(fixture.complete("class Test implements Runn§ {}"))).contains("Runnable");

    assertThat(labels(fixture.complete("class Test extends Runn§ {}"))).doesNotContain("Runnable");
    assertThat(labels(fixture.complete("class Test extends Str§ {}"))).doesNotContain("String");
    assertThat(labels(fixture.complete("class Test extends TimeU§ {}"))).doesNotContain("TimeUnit");

    assertThat(labels(fixture.complete("class Test implements ArrayD§ {}")))
        .doesNotContain("ArrayDeque");
    assertThat(labels(fixture.complete("class Test implements Str§ {}"))).doesNotContain("String");
    assertThat(labels(fixture.complete("class Test implements TimeU§ {}")))
        .doesNotContain("TimeUnit");

    assertThat(labels(fixture.complete("interface Test extends Runn§ {}"))).contains("Runnable");
    assertThat(labels(fixture.complete("interface Test extends ArrayD§ {}")))
        .doesNotContain("ArrayDeque");
    assertThat(labels(fixture.complete("interface Test extends TimeU§ {}")))
        .doesNotContain("TimeUnit");

    assertThat(labels(fixture.complete("record Test() implements Runn§ {}"))).contains("Runnable");
    assertThat(labels(fixture.complete("record Test() implements ArrayD§ {}")))
        .doesNotContain("ArrayDeque");

    assertThat(labels(fixture.complete("class Test extends § {}")))
        .doesNotContain("if", "return", "new", "var", "class", "interface");
    assertThat(labels(fixture.complete("class Test implements § {}")))
        .doesNotContain("if", "return", "new", "var", "class", "interface");
  }

  // ── simple name ───────────────────────────────────────────────────────────────

  @Test
  void simpleName_localVar_suggestedByPrefix() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        void m() {
                            String hello = "x";
                            hel§
                        }
                    }""")))
        .contains("hello");
  }

  @Test
  void simpleName_variableNotOfferedInOwnInitializer() {
    final var items =
        fixture.complete("class Test { void m() { String fooBar = \"x\"; String foo = fo§ } }");
    assertThat(itemWithFilterText(items, "foo")).isEmpty();
    assertThat(itemWithFilterText(items, "fooBar")).isPresent();
  }

  @Test
  void simpleName_classMember_matchingDeclaredType_rankedBeforeNonMatching() {
    final var items =
        fixture.complete(
            """
            class Test {
                String label = "x";
                int count = 0;
                String name() { return ""; }
                int size() { return 0; }
                void m() {
                    String result = §
                }
            }""");
    final var labelItem = itemWithFilterText(items, "label");
    final var countItem = itemWithFilterText(items, "count");
    assertThat(labelItem).isPresent();
    assertThat(countItem).isPresent();
    assertThat(labelItem.get().getSortText()).isLessThan(countItem.get().getSortText());
  }

  @Test
  void simpleName_objectMethods_demotedInStatementContext() {
    final var items =
        fixture.complete(
            """
            class Test {
                int zebra = 0;
                void m() {
                    §
                }
            }""");
    final var zebraItem = itemWithFilterText(items, "zebra");
    final var cloneItem = itemWithFilterText(items, "clone");
    final var waitItem = itemWithFilterText(items, "wait");
    assertThat(zebraItem).isPresent();
    assertThat(cloneItem).isPresent();
    assertThat(waitItem).isPresent();
    assertThat(cloneItem.get().getSortText()).startsWith("9_");
    assertThat(waitItem.get().getSortText()).startsWith("9_");
    assertThat(zebraItem.get().getSortText()).isNull();
  }

  @Test
  void simpleName_ownVoidMethod_shownInStatementContext() {
    assertThat(
            fixture
                .complete(
                    """
                    class Test {
                        void doWork() {}
                        void m() {
                            §
                        }
                    }""")
                .stream()
                .map(CompletionItem::getFilterText)
                .toList())
        .contains("doWork");
  }

  @Test
  void simpleName_voidMethod_excludedWhenExpectedTypeKnown() {
    // initializer position
    final var initializer =
        fixture.complete(
            """
            class Test {
                void doWork() {}
                String getValue() { return ""; }
                void m() {
                    String s = §
                }
            }""");
    assertThat(initializer).noneMatch(i -> "doWork".equals(i.getFilterText()));
    assertThat(initializer).extracting(CompletionItem::getFilterText).contains("getValue");
    assertThat(initializer)
        .extracting(CompletionItem::getFilterText)
        .doesNotContain("true", "false");
    final var booleanInitializer =
        fixture.complete(
            """
            class Test {
                boolean getFlag() { return true; }
                void m() {
                    boolean flag = §
                }
            }""");
    assertThat(booleanInitializer)
        .extracting(CompletionItem::getFilterText)
        .contains("getFlag", "true", "false")
        .doesNotContain("null");
    // argument position
    final var argument =
        fixture.complete(
            """
            class Test {
                void doWork() {}
                String getValue() { return ""; }
                void accept(String s) {}
                void m() {
                    accept(§);
                }
            }""");
    assertThat(argument).noneMatch(i -> "doWork".equals(i.getFilterText()));
    assertThat(argument).extracting(CompletionItem::getFilterText).contains("getValue");
  }

  @Test
  void simpleName_objectMethods_excludedWhenExpectedTypeKnown() {
    assertThat(
            fixture
                .complete(
                    """
                    class Test {
                        void m() {
                            String s = §
                        }
                    }""")
                .stream()
                .map(CompletionItem::getFilterText)
                .toList())
        .doesNotContainAnyElementsOf(List.of("wait", "finalize", "notify", "notifyAll"));
  }

  @Test
  void simpleName_constructorParam_suggestedInCtorBody() {
    // single constructor
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        Test(String value) {
                            val§
                        }
                    }""")))
        .contains("value");
    // second overload — findScopeMethodPath must pick the one containing the cursor
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        Test() {}
                        Test(String metricRegistry) {
                            met§
                        }
                    }""")))
        .contains("metricRegistry");
  }

  @Test
  void simpleName_expressionContext_noObjectMethods() {
    final var filterTexts =
        fixture
            .complete(
                """
                class Test {
                    void consume(String s) {}
                    String result() { return ""; }
                    void m() {
                        consume(§result());
                    }
                }""")
            .stream()
            .map(CompletionItem::getFilterText)
            .toList();
    assertThat(filterTexts).contains("result");
    assertThat(filterTexts)
        .doesNotContainAnyElementsOf(List.of("wait", "finalize", "notify", "notifyAll"));
  }

  // ── string literal / bare dot / no-op positions ──────────────────────────────

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

  // ── argument position ─────────────────────────────────────────────────────────

  @Test
  void argumentPosition_suggestsVisibleLocal() {
    // empty prefix
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        static class ReceiverFactory {
                            static Receiver create() { return new Receiver(); }
                        }

                        static class Receiver {
                            void accept(String value) {}
                        }

                        void m() {
                            String value = "";
                            ReceiverFactory.create().accept(§value);
                        }
                    }""")))
        .contains("value");
    // non-empty prefix
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        static void accept(String value) {}

                        void m() {
                            String value = "";
                            accept(val§);
                        }
                    }""")))
        .contains("value");
  }

  @Test
  void argumentPosition_zeroParamMethod_suppressesCompletions() {
    assertThat(
            fixture.complete(
                """
                class Test {
                    static void noArgs() {}
                    String result() { return ""; }
                    void m() {
                        String value = "";
                        noArgs(§);
                    }
                }"""))
        .isEmpty();
  }

  @Test
  void argumentPosition_lambdaParam_suggestsVisibleParam() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        static void consume(Object value) {}

                        void m(java.util.List<String> list) {
                            list.forEach(value -> consume(val§));
                        }
                    }""")))
        .contains("value");
  }

  @Test
  void argumentPosition_switchPatternVar_suggestsVisiblePatternVar() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        static void consume(Object value) {}

                        void m(Object object) {
                            switch (object) {
                                case String value -> consume(val§);
                                default -> {}
                            }
                        }
                    }""")))
        .contains("value");
  }

  @Test
  void argumentPosition_localVar_matchingParamType_rankedBeforeNonMatching() {
    final var items =
        fixture.complete(
            """
            class Foo {
                void target(String s) {}
                void test() {
                    String strVar = "hello";
                    int intVar = 42;
                    target(§);
                }
            }
            """);
    final var strVarItem = itemWithFilterText(items, "strVar");
    final var intVarItem = itemWithFilterText(items, "intVar");
    assertThat(strVarItem).isPresent();
    assertThat(intVarItem).isPresent();
    assertThat(strVarItem.get().getSortText()).isLessThan(intVarItem.get().getSortText());
  }

  @Test
  void argumentPosition_receiverQualifiedCall_localVarMatchingParamType_rankedFirst() {
    final var items =
        fixture.complete(
            """
            class Helper { void consume(String s) {} }
            class Foo {
                void test() {
                    Helper helper = new Helper();
                    String strVar = "hello";
                    int intVar = 42;
                    helper.consume(§);
                }
            }
            """);
    final var strVarItem = itemWithFilterText(items, "strVar");
    final var intVarItem = itemWithFilterText(items, "intVar");
    assertThat(strVarItem).isPresent();
    assertThat(intVarItem).isPresent();
    assertThat(strVarItem.get().getSortText()).isLessThan(intVarItem.get().getSortText());
  }

  @Test
  void argumentPosition_nonAssignableLocal_excluded() {
    final var items =
        labels(
            fixture.complete(
                """
                class Test {
                    void accept(String s) {}
                    void m() {
                        StringBuilder sb = new StringBuilder();
                        String text = "";
                        accept(§);
                    }
                }"""));
    assertThat(items).contains("text").doesNotContain("sb");
  }

  @Test
  void argumentPosition_nonAssignableLocal_excludedInReceiverQualifiedCall() {
    final var items =
        labels(
            fixture.complete(
                """
                class Helper { void consume(String s) {} }
                class Test {
                    void m() {
                        Helper helper = new Helper();
                        StringBuilder sb = new StringBuilder();
                        String text = "";
                        helper.consume(§);
                    }
                }"""));
    assertThat(items).contains("text").doesNotContain("sb");
  }

  @Test
  void argumentPosition_referenceTypeParam_booleansExcluded() {
    final var items =
        labels(
            fixture.complete(
                """
                class Test {
                    static class Foo {}
                    Foo getFoo() { return null; }
                    boolean isReady() { return true; }
                    void accept(Foo f) {}
                    void m() {
                        accept(§);
                    }
                }"""));
    assertThat(items).contains("getFoo()");
    assertThat(items).doesNotContain("isReady()", "true", "false");
  }

  // ── constructor call position ─────────────────────────────────────────────────

  @Test
  void constructorCall_suggestsLocalsAndExcludesNoise() {
    // suggests local variables
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        static class Receiver {
                            Receiver(String value) {}
                        }

                        void m() {
                            String value = "";
                            new Receiver(val§);
                        }
                    }""")))
        .contains("value");
    // no java.lang types or Object methods in second arg position
    final var secondArg =
        labels(
            fixture.complete(
                """
                class Test {
                    static class Receiver {
                        Receiver(String a, String b) {}
                    }

                    void m() {
                        String value = "";
                        new Receiver(value, val§);
                    }
                }"""));
    assertThat(secondArg).contains("value").doesNotContain("String", "Object", "Integer", "Thread");
    // no Object methods in any arg position
    final var firstArgFilterTexts =
        fixture
            .complete(
                """
                class Test {
                    static class Receiver {
                        Receiver(String value) {}
                    }

                    String result() { return ""; }

                    void m() {
                        new Receiver(§);
                    }
                }""")
            .stream()
            .map(CompletionItem::getFilterText)
            .toList();
    assertThat(firstArgFilterTexts).contains("result");
    assertThat(firstArgFilterTexts)
        .doesNotContainAnyElementsOf(List.of("wait", "finalize", "notify", "notifyAll"));
  }

  @Test
  void constructorCallArgument_nonAssignableLocal_excluded() {
    final var items =
        labels(
            fixture.complete(
                """
                class Test {
                    static class Receiver {
                        Receiver(String s) {}
                    }
                    void m() {
                        StringBuilder sb = new StringBuilder();
                        String text = "";
                        new Receiver(§);
                    }
                }"""));
    assertThat(items).contains("text").doesNotContain("sb");
  }

  // ── lambda body ───────────────────────────────────────────────────────────────

  @Test
  void lambdaBody_thisReceiver_membersReturned() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        String name = "x";
                        void m(java.util.List<String> list) {
                            list.forEach(s -> this.na§);
                        }
                    }""")))
        .contains("name");
  }

  @Test
  void lambdaBody_memberAccess_paramTypeResolved() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        void m(java.util.List<String> list) {
                            list.forEach(s -> s.to§);
                        }
                    }""")))
        .anyMatch(l -> l.startsWith("toLowerCase"));
  }

  // ── stream chains ─────────────────────────────────────────────────────────────

  @Test
  void streamChain_streamMethods_returned() {
    final var items =
        labels(
            fixture.complete(
                """
                class Test {
                    void m(java.util.List<String> list) {
                        list.stream().§
                    }
                }"""));
    assertThat(items).anyMatch(l -> l.startsWith("filter"));
    assertThat(items).anyMatch(l -> l.startsWith("map"));
  }

  @Test
  void lambdaBody_methodCallReceiver_typeResolved() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        void m(java.util.List<String> list) {
                            list.forEach(s -> s.trim().to§);
                        }
                    }""")))
        .anyMatch(l -> l.startsWith("toLowerCase"));
  }

  @Test
  void builderChain_methodCallChain_typeResolved() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        void m() {
                            new StringBuilder().append("x").ap§
                        }
                    }""")))
        .anyMatch(l -> l.startsWith("append"));
  }

  // ── multiline chains ──────────────────────────────────────────────────────────

  @Test
  void multilineChain_threeLines_completionOnSameLine() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        String m() {
                            return new StringBuilder()
                                    .append("a")
                                    .append("b").ap§
                        }
                    }""")))
        .anyMatch(l -> l.startsWith("append"));
  }

  @Test
  void multilineChain_cursorAtFirstCall_continuationLinesBelow() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        StringBuilder makeBuilder() { return new StringBuilder(); }
                        void m() {
                            makeBuilder().§
                                    .append("x")
                                    .append("y");
                        }
                    }""")))
        .anyMatch(l -> l.startsWith("append"));
  }

  @Test
  void argumentPosition_insideChainInsideSuper_booleansExcluded() {
    final var items =
        labels(
            fixture.complete(
                """
                class Base {
                    Base(String s) {}
                }
                class Child extends Base {
                    static class Builder {
                        Builder value(String s) { return this; }
                        String build() { return ""; }
                    }
                    static Builder builder() { return new Builder(); }
                    String getStr() { return ""; }
                    boolean isReady() { return true; }
                    Child(String input) {
                        super(
                            builder()
                                .value(§)
                                .build());
                    }
                }"""));
    assertThat(items).contains("getStr()");
    assertThat(items).doesNotContain("isReady()", "true", "false");
  }

  @Test
  void multilineChain_simpleIdentifierReceiver_crossLine() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        void m() {
                            String s = "hello";
                            s
                                .§toUp
                        }
                    }""")))
        .anyMatch(l -> l.startsWith("toUpper"));
  }

  @Test
  void multilineChain_receiverOnPreviousLine_crossLineCompletion() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        String foo() { return ""; }
                        void m() {
                            foo()
                                .toL§
                        }
                    }""")))
        .anyMatch(l -> l.startsWith("toLowerCase"));
  }

  // ── stale cache ───────────────────────────────────────────────────────────────

  @Test
  void staleCacheDotTrigger_importSuggested() {
    // non-static — two levels
    assertThat(
            labels(
                fixture.completeWithCache(
                    "import java;\nclass Test {}", "import java.§;\n\nclass Test {}")))
        .contains("util");
    assertThat(
            labels(
                fixture.completeWithCache(
                    "import java.util;\nclass Test {}", "import java.util.§;\n\nclass Test {}")))
        .contains("Collections");
    // static
    assertThat(
            labels(
                fixture.completeWithCache(
                    "import static java;\nclass Test {}",
                    "import static java.§;\n\nclass Test {}")))
        .contains("util");
  }

  @Test
  void streamChain_staleCacheFilterLambda_typeResolved() {
    assertThat(
            labels(
                fixture.completeWithCache(
                    """
                    class Test {
                        void m(java.util.List<String> list) {
                        }
                    }""",
                    """
                    class Test {
                        void m(java.util.List<String> list) {
                            list.stream().filter(s -> s.to§);
                        }
                    }""")))
        .anyMatch(l -> l.startsWith("toLowerCase"));
  }

  @Test
  void memberAccess_staleCacheNewLine_typeResolved() {
    assertThat(
            labels(
                fixture.completeWithCache(
                    """
                    class Test {
                        java.util.List<String> getList() { return null; }
                        void m() {
                        }
                    }""",
                    """
                    class Test {
                        java.util.List<String> getList() { return null; }
                        void m() {
                            getList().sub§
                        }
                    }""")))
        .anyMatch(l -> l.startsWith("subList"));
  }

  @Test
  void memberAccess_staleCacheReplacedExpression_typeResolved() {
    assertThat(
            labels(
                fixture.completeWithCache(
                    """
                    class Test {
                        String foo() { return ""; }
                        int bar() { return 0; }
                        void m() {
                            bar();
                        }
                    }""",
                    """
                    class Test {
                        String foo() { return ""; }
                        int bar() { return 0; }
                        void m() {
                            foo().toL§
                        }
                    }""")))
        .anyMatch(l -> l.startsWith("toLowerCase"));
  }

  // ── real-world patterns ───────────────────────────────────────────────────────

  @Test
  void memberAccess_overloadedMethodCallReceiver_correctReturnTypeResolved() {
    final var items =
        fixture.complete(
            """
            class Test {
                static class IntAssert {
                    IntAssert isEqualTo(int v) { return this; }
                    IntAssert isGreaterThan(int v) { return this; }
                }
                static class StrAssert {
                    StrAssert isEqualTo(String v) { return this; }
                    StrAssert contains(String s) { return this; }
                }
                static IntAssert assertThat(int v) { return new IntAssert(); }
                static StrAssert assertThat(String v) { return new StrAssert(); }
                int getStatus() { return 200; }
                void m() {
                    assertThat(getStatus()).isEqual§
                }
            }""");
    assertThat(labels(items)).anyMatch(l -> l.startsWith("isEqualTo"));
    assertThat(items).noneMatch(i -> i.getLabel().startsWith("contains"));
  }

  @Test
  void memberAccess_methodCallReceiver_argumentContainsClassLiteral_returnTypeResolved() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        static class StrAssert {
                            StrAssert isEqualTo(String v) { return this; }
                            StrAssert contains(String s) { return this; }
                            StrAssert startsWith(String s) { return this; }
                        }
                        static StrAssert assertThat(String v) { return new StrAssert(); }
                        static <T> T readEntity(Class<T> cls) { return null; }
                        void m() {
                            assertThat(readEntity(String.class)).isEqual§
                        }
                    }""")))
        .anyMatch(l -> l.startsWith("isEqualTo"));
  }

  @Test
  void memberAccess_methodParam_insideConstructorCallArg_typeResolved() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        static class Config {
                            String url() { return ""; }
                            String username() { return ""; }
                        }
                        static class Connection {
                            Connection(String url) {}
                        }
                        void m(Config config) {
                            new Connection(config.ur§);
                        }
                    }""")))
        .anyMatch(l -> l.startsWith("url"));
  }

  @Test
  void memberAccess_localVar_insideStaticFactoryCallArg_typeResolved() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        static class Builder {
                            Object build() { return null; }
                            Builder credential(String c) { return this; }
                        }
                        static Object create(Object settings) { return null; }
                        void m() {
                            Builder settingsBuilder = new Builder();
                            create(settingsBuilder.buil§);
                        }
                    }""")))
        .anyMatch(l -> l.startsWith("build"));
  }

  // ── gap regressions ───────────────────────────────────────────────────────────

  @Test
  void memberAccess_item_hasTextEdit() {
    // gap #2: Every item must carry a textEdit so prefix replacement is correct
    final var items =
        fixture.complete("class Test { void m(java.util.ArrayList<String> l) { l.toS§ } }");
    assertThat(items).isNotEmpty();
    assertThat(items).allMatch(i -> i.getTextEdit() != null);
  }

  @Test
  void memberAccess_objectMethods_rankBelowDomainMembers() {
    // gap #3: equals/hashCode/toString sort below domain methods
    final var items =
        fixture.complete(
            """
            class Test {
                void m(java.util.ArrayList<String> list) {
                    list.§
                }
            }""");
    final var sizeItem = itemLabeled(items, "size()");
    final var equalsItem =
        items.stream().filter(i -> i.getLabel().startsWith("equals")).findFirst();
    assertThat(sizeItem).isPresent();
    assertThat(equalsItem).isPresent();
    assertThat(sizeItem.get().getSortText()).isLessThan(equalsItem.get().getSortText());
  }

  @Test
  void memberAccess_classFieldReceiver_typeResolved() {
    // gap #7: class field receiver — scanForLocalDeclaration only checks method locals
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        java.util.ArrayList<String> handler = new java.util.ArrayList<>();
                        void m() {
                            handler.sub§
                        }
                    }""")))
        .anyMatch(l -> l.startsWith("subList"));
  }

  @Test
  void simpleName_emptyPrefix_noTypeIndexItems() {
    // gap #12: empty prefix must not offer type-index items in class body or method body
    assertThat(fixture.complete("class Test { § }"))
        .noneMatch(i -> "ArrayDeque".equals(i.getLabel()));
    assertThat(fixture.complete("class Test { void m() { § } }"))
        .noneMatch(i -> "ArrayDeque".equals(i.getLabel()));
  }

  @Test
  void simpleName_staticImportedMethod_offeredWithoutQualifier() {
    // gap A: static-import members not offered as simple names — single and wildcard
    assertThat(
            labels(
                fixture.complete(
                    """
                    import static java.util.Objects.requireNonNull;

                    class Test {
                        void m(String s) {
                            requireN§
                        }
                    }""")))
        .anyMatch(l -> l.startsWith("requireNonNull"));
    assertThat(
            labels(
                fixture.complete(
                    """
                    import static java.util.Objects.*;

                    class Test {
                        void m(String s) {
                            requireN§
                        }
                    }""")))
        .anyMatch(l -> l.startsWith("requireNonNull"));
    assertThat(
            labels(
                fixture.complete(
                    """
                    import static java.util.concurrent.TimeUnit.SECONDS;

                    class Test {
                        void m() {
                            SE§
                        }
                    }""")))
        .contains("SECONDS");
  }

  // ── presentation details ─────────────────────────────────────────────────────

  @Test
  void completionItem_method_hasCorrectFilterTextAndSnippetInsertFormat() {
    final var item =
        fixture.complete("class Test { void m(java.util.ArrayList<String> l) { l.sub§ } }").stream()
            .filter(i -> i.getLabel().startsWith("subList("))
            .findFirst();
    assertThat(item).isPresent();
    assertThat(item.get().getFilterText()).isEqualTo("subList");
    assertThat(item.get().getInsertTextFormat()).isEqualTo(InsertTextFormat.Snippet);
    assertThat(item.get().getInsertText()).contains("$");
  }

  @Test
  void typeIndex_itemKind_interfaceAndEnumMappedCorrectly() {
    assertThat(itemLabeled(fixture.complete("class Test implements Runn§ {}"), "Runnable"))
        .hasValueSatisfying(i -> assertThat(i.getKind()).isEqualTo(CompletionItemKind.Interface));
    assertThat(itemLabeled(fixture.complete("class Test { TimeU§ field; }"), "TimeUnit"))
        .hasValueSatisfying(i -> assertThat(i.getKind()).isEqualTo(CompletionItemKind.Enum));
  }

  @Test
  void completionItem_importEdit_insertedAfterLastExistingImport() throws IOException {
    localFixture =
        new CompletionFixture(
            CompletionFixture.typeIndex(
                tmp.resolve("index.json"),
                CompletionFixture.typeEntry("ArrayList", "java.util.ArrayList", TypeKind.CLASS)));
    final var item =
        itemLabeled(
            localFixture.complete(
                """
                package example;

                import java.util.List;

                class Test {
                  void accept(Object v) {}
                  void m() { accept(new ArrayL§); }
                }"""),
            "ArrayList");
    assertThat(item).isPresent();
    final var edit = item.get().getAdditionalTextEdits().getFirst();
    assertThat(edit.getNewText()).isEqualTo("import java.util.ArrayList;\n");
    assertThat(edit.getRange()).isEqualTo(new Range(new Position(3, 0), new Position(3, 0)));
  }

  // ── type index ────────────────────────────────────────────────────────────────

  @Test
  void typeIndex_fieldDeclaration_itemHasCorrectMetadataAndResultIsIncomplete() {
    // gap #4: kind/detail must be populated; result must be marked incomplete
    final var outcome = fixture.outcome("class Test { ArrayD§ field; }");
    final var item = itemLabeled(outcome.items(), "ArrayDeque");
    assertThat(item).isPresent();
    assertThat(item.get().getKind()).isEqualTo(CompletionItemKind.Class);
    assertThat(item.get().getDetail()).isEqualTo("java.util.ArrayDeque");
    assertThat(outcome.incomplete()).isTrue();
  }

  @Test
  void typeIndex_suggestsIndexedTypeAtDeclarationSites() {
    // TYPE_REFERENCE positions: method param, generic arg, field, return type, extends, implements
    assertThat(labels(fixture.complete("class Test { void m(ArrayD§ p) {} }")))
        .contains("ArrayDeque");
    assertThat(
            labels(fixture.complete("class Test { void m() { java.util.List<ArrayD§> list; } }")))
        .contains("ArrayDeque");
    assertThat(labels(fixture.complete("class Test { ArrayD§ field; }"))).contains("ArrayDeque");
    assertThat(labels(fixture.complete("class Test { ArrayD§ getQ() { return null; } }")))
        .contains("ArrayDeque");
    assertThat(labels(fixture.complete("class Test extends AbstractL§ {}")))
        .contains("AbstractList");
    assertThat(labels(fixture.complete("class Test implements Runn§ {}"))).contains("Runnable");
  }

  @Test
  void typeIndex_suggestsIndexedTypeInCodeStatements() {
    // SIMPLE_NAME positions with uppercase prefix: method body, ctor body, local var, new-prefix
    assertThat(labels(fixture.complete("class Test { void m() { ArrayD§ } }")))
        .contains("ArrayDeque");
    assertThat(labels(fixture.complete("class Test { void m() { ArrayD§ local; } }")))
        .contains("ArrayDeque");
    assertThat(labels(fixture.complete("class Test { Test() { ArrayD§ } }")))
        .contains("ArrayDeque");
    assertThat(labels(fixture.complete("class Test { void m() { new StringBuilder(); ArrayD§ } }")))
        .contains("ArrayDeque");
    assertThat(labels(fixture.complete("class Test { void m() { Object x = new ArrayD§ } }")))
        .contains("ArrayDeque");
  }

  @Test
  void typeIndex_classLiteral_suggestsIndexedType() {
    assertThat(
            labels(
                fixture.complete(
                    "class Test { void register(Class<?> c) {} void m() { register(ArrayD§.class); } }")))
        .contains("ArrayDeque");
  }

  @Test
  void typeIndex_candidates_javaLangRanksBeforeOtherPackages() throws IOException {
    localFixture =
        new CompletionFixture(
            CompletionFixture.typeIndex(
                tmp.resolve("index.json"),
                CompletionFixture.typeEntry(
                    "StringJoiner", "java.util.StringJoiner", TypeKind.CLASS),
                CompletionFixture.typeEntry(
                    "StringBuilder", "java.lang.StringBuilder", TypeKind.CLASS)));
    final var items = labels(localFixture.complete("class Test { Str§ field; }"));
    assertThat(items.indexOf("StringBuilder")).isLessThan(items.indexOf("StringJoiner"));
  }

  // ── java.lang fallback (no type index) ───────────────────────────────────────

  @Test
  void methodBody_typePrefix_suggestsJavaLangType_withoutTypeIndex() {
    // engine has an empty type index — String must still appear via the java.lang fallback
    localFixture = new CompletionFixture();
    assertThat(labels(localFixture.complete("class Test { void m() { Str§ value; } }")))
        .contains("String");
    assertThat(labels(localFixture.complete("class Test { void m() { Str§ } }")))
        .contains("String");
  }

  @Test
  void constructorCall_typePrefix_suggestsJavaLangType_withoutTypeIndex() {
    localFixture = new CompletionFixture();
    assertThat(labels(localFixture.complete("class Test { void m() { Object o = new Str§ } }")))
        .contains("String");
  }

  // ── type index: unimported simple name fallback (gap B) ───────────────────────

  @Test
  void memberAccess_unimportedSimpleName_typeIndexFallback_suggestsMembers() throws IOException {
    localFixture =
        new CompletionFixture(
            CompletionFixture.typeIndex(
                tmp.resolve("index.json"),
                CompletionFixture.typeEntry("Objects", "java.util.Objects", TypeKind.CLASS)));
    assertThat(
            labels(
                localFixture.complete(
                    """
                    import static java.util.Objects.requireNonNull;

                    class Test {
                        void m(String s) {
                            Objects.§
                        }
                    }""")))
        .anyMatch(l -> l.startsWith("requireNonNull"));
  }

  // ── FQN navigation: JPMS visibility ──────────────────────────────────────────

  @Test
  void fqnNavigation_isAccessible_filtersNonExportedPackageTypes() throws IOException {
    final var lib = buildExampleLib();
    localFixture = new CompletionFixture(WorkspaceTypeIndex.empty(), tmp);

    assertThat(
            labels(
                localFixture.completeWithJpms(
                    """
                    package com.example.app;
                    class Test {
                        void m() { com.example.lib.api.§ }
                    }""",
                    lib.moduleInfo(),
                    lib.modulePath())))
        .contains("ApiType");

    assertThat(
            labels(
                localFixture.completeWithJpms(
                    """
                    package com.example.app;
                    class Test {
                        void m() { com.example.lib.internal.§ }
                    }""",
                    lib.moduleInfo(),
                    lib.modulePath())))
        .doesNotContain("InternalType");

    // import declaration
    assertThat(
            labels(
                localFixture.completeWithJpms(
                    """
                    package com.example.app;
                    import com.example.lib.internal.§;
                    class Test {}""",
                    lib.moduleInfo(),
                    lib.modulePath())))
        .doesNotContain("InternalType");

    // class body type reference
    assertThat(
            labels(
                localFixture.completeWithJpms(
                    """
                    package com.example.app;
                    class Test {
                        com.example.lib.internal.§ field;
                    }""",
                    lib.moduleInfo(),
                    lib.modulePath())))
        .doesNotContain("InternalType");
  }

  @Test
  void fqnNavigation_isAccessible_staticImport_filtersNonExportedPackageTypes() throws IOException {
    final var lib = buildExampleLib();
    localFixture = new CompletionFixture(WorkspaceTypeIndex.empty(), tmp);
    assertThat(
            labels(
                localFixture.completeWithJpms(
                    """
                    package com.example.app;
                    import static com.example.lib.internal.§;
                    class Test {}""",
                    lib.moduleInfo(),
                    lib.modulePath())))
        .doesNotContain("InternalType");
  }

  @Test
  void fqnNavigation_subPackageStream_filtersNonExportedSubPackages() throws IOException {
    final var lib = buildExampleLib();
    localFixture = new CompletionFixture(WorkspaceTypeIndex.empty(), tmp);

    final List<String> segments =
        labels(
            localFixture.completeWithJpms(
                """
                package com.example.app;
                import com.example.lib.§;
                class Test {}""",
                lib.moduleInfo(),
                lib.modulePath()));

    assertThat(segments).contains("api");
    assertThat(segments).doesNotContain("internal");
  }

  @Test
  void fqnNavigation_subPackageStream_methodBody_filtersNonExportedSubPackages()
      throws IOException {
    final var lib = buildExampleLib();
    localFixture = new CompletionFixture(WorkspaceTypeIndex.empty(), tmp);

    final List<String> segments =
        labels(
            localFixture.completeWithJpms(
                """
                package com.example.app;
                class Test {
                    void m() { com.example.lib.§ }
                }""",
                lib.moduleInfo(),
                lib.modulePath()));

    assertThat(segments).contains("api");
    assertThat(segments).doesNotContain("internal");
  }

  @Test
  void fqnNavigation_classBody_packagePrefix_suggestsSubPackages() throws IOException {
    final var lib = buildExampleLib();
    localFixture = new CompletionFixture(WorkspaceTypeIndex.empty(), tmp);

    final List<String> segments =
        labels(
            localFixture.completeWithJpms(
                """
                package com.example.app;
                class Test {
                    com.example.lib.§ field;
                }""",
                lib.moduleInfo(),
                lib.modulePath()));

    assertThat(segments).contains("api");
    assertThat(segments).doesNotContain("internal");
  }

  @Test
  void fqnNavigation_subPackageStream_filtersNonTransitiveModuleSubPackages() throws IOException {
    final var lib = buildLibWithHiddenDep();
    localFixture = new CompletionFixture(WorkspaceTypeIndex.empty(), tmp);

    final List<String> segments =
        labels(
            localFixture.completeWithJpms(
                """
                package com.example.app;
                import com.example.§;
                class Test {}""",
                lib.moduleInfo(),
                lib.modulePath()));

    assertThat(segments).contains("lib");
    assertThat(segments).doesNotContain("other");
  }

  @Test
  void fqnNavigation_jpmsReadableModule_suggestsTypes() throws IOException {
    localFixture = new CompletionFixture(WorkspaceTypeIndex.empty(), tmp);
    assertThat(
            labels(
                localFixture.completeWithJpms(
                    """
                    package com.example.app;
                    class Test {
                        void m() {
                            javax.swing.§
                        }
                    }""",
                    """
                    module com.example.app {
                        requires java.desktop;
                    }""")))
        .anyMatch(l -> l.startsWith("J"));
  }

  @Test
  void fqnNavigation_jpmsUnreadableModule_suggestsNothing() throws IOException {
    localFixture = new CompletionFixture(WorkspaceTypeIndex.empty(), tmp);
    assertThat(
            localFixture.completeWithJpms(
                """
                package com.example.app;
                class Test {
                    void m() {
                        javax.swing.§
                    }
                }""",
                """
                module com.example.app {
                }"""))
        .isEmpty();
  }

  // ── type index: JPMS visibility ───────────────────────────────────────────────

  @Test
  void typeIndex_jpmsReadablePackage_suggestsIndexedType() throws IOException {
    localFixture =
        new CompletionFixture(
            CompletionFixture.typeIndex(
                tmp.resolve("index.json"),
                CompletionFixture.typeEntry("JButton", "javax.swing.JButton", TypeKind.CLASS)),
            tmp);
    assertThat(
            labels(
                localFixture.completeWithJpms(
                    """
                    package com.example.app;

                    class Test {
                        JBut§ field;
                    }""",
                    """
                    module com.example.app {
                      requires java.desktop;
                    }""")))
        .contains("JButton");
  }

  @Test
  void typeIndex_jpmsUnreadablePackage_doesNotSuggestIndexedType() throws IOException {
    localFixture =
        new CompletionFixture(
            CompletionFixture.typeIndex(
                tmp.resolve("index.json"),
                CompletionFixture.typeEntry("JButton", "javax.swing.JButton", TypeKind.CLASS)),
            tmp);
    assertThat(
            labels(
                localFixture.completeWithJpms(
                    """
                    package com.example.app;

                    class Test {
                        JBut§ field;
                    }""",
                    """
                    module com.example.app {
                    }""")))
        .doesNotContain("JButton");
  }

  @Test
  void typeIndex_platformType_survivesValidator_jpmsModule() throws IOException {
    localFixture =
        new CompletionFixture(
            CompletionFixture.typeIndex(
                tmp.resolve("index.json"),
                CompletionFixture.typeEntry("Objects", "java.util.Objects", TypeKind.CLASS)),
            tmp);
    assertThat(
            labels(
                localFixture.completeWithJpms(
                    """
                    package com.example.app;
                    class Test {
                        void m() {
                            Obj§
                        }
                    }""",
                    """
                    module com.example.app {
                    }""")))
        .anyMatch(l -> l.startsWith("Objects"));
  }

  // ── argument position: importable types ───────────────────────────────────────

  @Test
  void argumentPosition_importableTypeAddsImport() throws IOException {
    localFixture =
        new CompletionFixture(
            CompletionFixture.typeIndex(
                tmp.resolve("index.json"),
                CompletionFixture.typeEntry("ArrayList", "java.util.ArrayList", TypeKind.CLASS)));
    assertThatCompletion(
            localFixture.complete(
                """
                class Test {
                  void accept(Object value) {}

                  void m() {
                    accept(new ArrayL§);
                  }
                }
                """))
        .containsLabel("ArrayList")
        .item("ArrayList")
        .hasImportEdit("java.util.ArrayList");
  }

  @Test
  void argumentPosition_staticMemberFit_offeredWithStaticImportEdit() throws IOException {
    localFixture =
        new CompletionFixture(
            CompletionFixture.typeIndex(
                tmp.resolve("index.json"),
                CompletionFixture.typeEntry(
                    "StringSources", "example.StringSources", TypeKind.CLASS)));
    assertThatCompletion(
            localFixture.complete(
                """
                package example;

                class StringSources {
                  public static String sample() { return ""; }
                  public static int count() { return 0; }
                }

                class Test {
                  void accept(String value) {}

                  void m() {
                    accept(StringS§);
                  }
                }
                """))
        .containsLabel("sample()")
        .doesNotContainLabel("count()")
        .item("sample()")
        .hasStaticImportEdit("example.StringSources.sample");
  }

  record ExampleLib(List<String> modulePath, String moduleInfo) {}

  private ExampleLib buildExampleLib() throws IOException {
    final Path libSrc = tmp.resolve("lib-src");
    Files.createDirectories(libSrc.resolve("com/example/lib/api"));
    Files.createDirectories(libSrc.resolve("com/example/lib/internal"));
    Files.writeString(
        libSrc.resolve("module-info.java"),
        """
        module com.example.lib {
            exports com.example.lib.api;
        }""");
    Files.writeString(
        libSrc.resolve("com/example/lib/api/ApiType.java"),
        "package com.example.lib.api; public class ApiType {}");
    Files.writeString(
        libSrc.resolve("com/example/lib/internal/InternalType.java"),
        "package com.example.lib.internal; public class InternalType {}");
    final Path libOut = tmp.resolve("lib-out");
    TestCompiler.compileToDir(
        libOut,
        List.of(),
        List.of(),
        libSrc.resolve("module-info.java"),
        libSrc.resolve("com/example/lib/api/ApiType.java"),
        libSrc.resolve("com/example/lib/internal/InternalType.java"));
    return new ExampleLib(
        List.of("--module-path", libOut.toString()),
        """
        module com.example.app {
            requires com.example.lib;
        }""");
  }

  private ExampleLib buildLibWithHiddenDep() throws IOException {
    final Path otherSrc = tmp.resolve("other-src");
    Files.createDirectories(otherSrc.resolve("com/example/other"));
    Files.writeString(
        otherSrc.resolve("module-info.java"),
        "module com.example.other { exports com.example.other; }");
    Files.writeString(
        otherSrc.resolve("com/example/other/OtherType.java"),
        "package com.example.other; public class OtherType {}");
    final Path otherOut = tmp.resolve("other-out");
    TestCompiler.compileToDir(
        otherOut,
        List.of(),
        List.of(),
        otherSrc.resolve("module-info.java"),
        otherSrc.resolve("com/example/other/OtherType.java"));

    final Path libSrc = tmp.resolve("lib-src");
    Files.createDirectories(libSrc.resolve("com/example/lib"));
    Files.writeString(
        libSrc.resolve("module-info.java"),
        """
        module com.example.lib {
            requires com.example.other;
            exports com.example.lib;
        }""");
    Files.writeString(
        libSrc.resolve("com/example/lib/LibType.java"),
        "package com.example.lib; public class LibType {}");
    final Path libOut = tmp.resolve("lib-out");
    TestCompiler.compileToDir(
        libOut,
        List.of(),
        List.of("--module-path", otherOut.toString()),
        libSrc.resolve("module-info.java"),
        libSrc.resolve("com/example/lib/LibType.java"));

    return new ExampleLib(
        List.of("--module-path", libOut + ":" + otherOut),
        """
        module com.example.app {
            requires com.example.lib;
        }""");
  }
}
