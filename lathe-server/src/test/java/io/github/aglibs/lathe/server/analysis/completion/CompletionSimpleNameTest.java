package io.github.aglibs.lathe.server.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.typeindex.TypeKind;
import java.util.List;
import java.util.stream.Stream;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CompletionSimpleNameTest extends CompletionTestSupport {

  private static void assertLabelBefore(
      final List<String> labels, final String earlier, final String later) {
    assertThat(labels).contains(earlier, later);
    assertThat(labels.indexOf(earlier)).isLessThan(labels.indexOf(later));
  }

  @Test
  void simpleName_uppercasePrefix_inRecoveredStatementsIncludesVisibleValuesAndTypes()
      throws Exception {
    localFixture =
        new CompletionFixture(
            CompletionFixture.typeIndex(
                tmp.resolve("index.json"),
                CompletionFixture.typeEntry("Logger", "java.util.logging.Logger", TypeKind.CLASS)));

    final List<String> methodBodyItems =
        labels(
            localFixture.complete(
                """
                class Test {
                    static final Object LOGGER = new Object();

                    static class Nested {
                        void m() {
                            LOG§
                        }
                    }
                }"""));
    final List<String> ifBodyItems =
        labels(
            localFixture.complete(
                """
                class Test {
                    static final Object LOGGER = new Object();

                    static class Nested {
                        void m(boolean ready) {
                            if (ready) {
                                LOG§
                            }
                        }
                    }
                }"""));

    assertThat(methodBodyItems).contains("LOGGER", "Logger");
    assertThat(ifBodyItems).contains("LOGGER", "Logger");
  }

  @Test
  void variableInitializer_referenceLocal_rankedAfterAssignableLocal() {
    final var items =
        fixture.complete(
            """
            class Test {
                void m() {
                    StringBuilder sb = new StringBuilder();
                    String text = "";
                    String result = §
                }
            }""");
    assertThat(labels(items)).contains("text", "sb");
    final var textItem = itemWithFilterText(items, "text").orElseThrow();
    final var sbItem = itemWithFilterText(items, "sb").orElseThrow();
    assertThat(textItem.getSortText()).isLessThan(sbItem.getSortText());
  }

  @Test
  void varInitializer_doesNotUseEnclosingMethodReturnType() {
    final var items =
        labels(
            fixture.complete(
                """
                class Test {
                    static class Service {}
                    Service fromConfig() { return null; }
                    Service m() {
                        String text = "";
                        var value = §
                    }
                }"""));

    assertLabelBefore(items, "text", "fromConfig");
  }

  @Test
  void assignmentToFreshLocal_usesAssigneeTypeNotEnclosingReturnType() {
    final var items =
        labels(
            fixture.complete(
                """
                class Test {
                    static class Service {}
                    Service fromConfig() { return null; }
                    Service m() {
                        String text = "";
                        String value = "";
                        value = §
                    }
                }"""));

    assertLabelBefore(items, "text", "fromConfig");
  }

  @Test
  void assignment_referenceType_booleanCandidatesExcluded() {
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
    assertThat(items).contains("getFoo");
    assertThat(items).doesNotContain("isReady()", "true", "false");
  }

  @Test
  void equalityComparison_enumLhs_suggestsEnumConstants() {
    final var items =
        labels(
            fixture.complete(
                """
                class Test {
                    enum Status { ACTIVE, INACTIVE, PENDING }
                    void m(Status s) {
                        if (s == §) {}
                    }
                }"""));
    assertThat(items).contains("Status.ACTIVE", "Status.INACTIVE", "Status.PENDING");
    assertThat(items).doesNotContain("if", "for", "while");
  }

  @Test
  void equalityComparison_enumLhs_suggestsUnqualifiedConstantsWithStaticImport() {
    // When the enum is in scope, constants should also appear unqualified
    final var items =
        labels(
            fixture.complete(
                """
                class Test {
                    enum Status { ACTIVE, INACTIVE }
                    void m(Status s) {
                        if (s == S§) {}
                    }
                }"""));
    assertThat(items).contains("Status.ACTIVE", "Status.INACTIVE");
  }

  @Test
  void equalityComparison_methodCallLhs_suggestsEnumConstants() {
    final var items =
        labels(
            fixture.complete(
                """
                class External {
                    interface Event {
                        enum Status { ACTIVE, INACTIVE }
                        Status getStatus();
                    }
                }
                class Test {
                    void m(External.Event event) {
                        if (event.getStatus() == §) {}
                    }
                }"""));
    assertThat(items).contains("External.Event.Status.ACTIVE", "External.Event.Status.INACTIVE");
  }

  @Test
  void simpleName_afterReferenceExpression_offersInstanceof() {
    final List<String> items =
        labels(
            fixture.complete(
                """
                class Test {
                    void m(Object value) {
                        if (value ins§) {}
                    }
                }"""));

    assertThat(items).contains("instanceof");
  }

  @Test
  void simpleName_afterPrimitiveExpression_omitsInstanceof() {
    final List<String> items =
        labels(
            fixture.complete(
                """
                class Test {
                    void m(int value) {
                        if (value ins§) {}
                    }
                }"""));

    assertThat(items).doesNotContain("instanceof");
  }

  @Test
  void simpleName_atStatementStart_omitsInstanceof() {
    final List<String> items =
        labels(
            fixture.complete(
                """
                class Test {
                    void m() {
                        ins§
                    }
                }"""));

    assertThat(items).doesNotContain("instanceof");
  }

  @Test
  void returnPosition_referenceLocal_rankedAfterAssignableLocal() {
    final var items =
        fixture.complete(
            """
            class Test {
                StringBuilder sb = new StringBuilder();
                String text = "";
                String getValue() {
                    return §;
                }
            }""");
    assertThat(labels(items)).contains("text", "sb");
    final var textItem = itemWithFilterText(items, "text").orElseThrow();
    final var sbItem = itemWithFilterText(items, "sb").orElseThrow();
    assertThat(textItem.getSortText()).isLessThan(sbItem.getSortText());
  }

  // ── switch / inner-class local visibility (pure data variations) ─────────────

  static Stream<Arguments> simpleName_localVisibleAtUsageSite_cases() {
    return Stream.of(
        Arguments.of(
            "innerClassMethod",
            """
            class Outer {
                private static class Inner {
                    static void accept(String s) {}
                    void process(int x) {
                        switch (x) {
                            case 1:
                                final String localVar = "hello";
                                accept(loc§);
                                break;
                        }
                    }
                }
            }""",
            "localVar"),
        Arguments.of(
            "switchCaseArm",
            """
            class Test {
                static void accept(String s) {}
                void m(int x) {
                    switch (x) {
                        case 1:
                            final String result = "hello";
                            accept(res§);
                            break;
                    }
                }
            }""",
            "result"),
        Arguments.of(
            "forLoopNestedInSwitch",
            """
            class Test {
                static void accept(String s) {}
                void m(int x) {
                    switch (x) {
                        case 1:
                            for (String item : java.util.List.of("a")) {
                                accept(it§);
                            }
                            break;
                    }
                }
            }""",
            "item"));
  }

  @ParameterizedTest(name = "simpleName_{0}_local_visibleAtUsage")
  @MethodSource("simpleName_localVisibleAtUsageSite_cases")
  void simpleName_local_visibleAtUsage(
      final String scenario, final String source, final String expected) {
    assertThat(labels(fixture.complete(source))).contains(expected);
  }

  @Test
  void simpleName_switchCaseLabel_stringSubject_suppressesTypeIndexClasses() {
    final var items =
        labels(
            fixture.complete(
                """
                class Test {
                    String m(String type) {
                        return switch (type) {
                            case §
                        };
                    }
                }"""));

    assertThat(items)
        .doesNotContain(
            "ArrayDeque",
            "AbstractList",
            "Integer",
            "Runnable",
            "String",
            "StringBuilder",
            "TimeUnit");
  }

  @Test
  void variableInitializer_referenceMethod_rankedAfterAssignableMethod() {
    final var items =
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
            }""");
    assertThat(labels(items)).contains("getFoo", "getString", "getSb");
    final var fooItem = itemWithFilterText(items, "getFoo").orElseThrow();
    final var stringItem = itemWithFilterText(items, "getString").orElseThrow();
    final var sbItem = itemWithFilterText(items, "getSb").orElseThrow();
    assertThat(fooItem.getSortText()).isLessThan(stringItem.getSortText());
    assertThat(fooItem.getSortText()).isLessThan(sbItem.getSortText());
    assertThat(labels(items)).doesNotContain("doSomething()", "isReady()", "true", "false");
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
    assertThat(labels(items)).contains("fooBar");
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
    assertThat(labels(items)).contains("label", "count");
    final var labelItem = itemWithFilterText(items, "label").orElseThrow();
    final var countItem = itemWithFilterText(items, "count").orElseThrow();
    assertThat(labelItem.getSortText()).isLessThan(countItem.getSortText());
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
    assertThat(labels(items)).contains("zebra", "clone", "wait");
    final var zebraItem = itemWithFilterText(items, "zebra").orElseThrow();
    final var cloneItem = itemWithFilterText(items, "clone").orElseThrow();
    final var waitItem = itemWithFilterText(items, "wait").orElseThrow();
    assertThat(cloneItem.getSortText()).startsWith("9_");
    assertThat(waitItem.getSortText()).startsWith("9_");
    assertThat(zebraItem.getSortText()).isNull();
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
  void simpleName_overloadedMethods_preservesEachOverload() {
    final List<CompletionItem> items =
        fixture.complete(
            """
            class Test {
                static Test forTesting() {
                    return null;
                }

                static Test forTesting(String value) {
                    return null;
                }

                void m() {
                    forT§
                }
            }""");

    assertThat(labels(items)).contains("forTesting");
    assertThat(itemWithLabelDetail(items, "forTesting", "()")).isPresent();
    assertThat(itemWithLabelDetail(items, "forTesting", "(String value)")).isPresent();
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
  void simpleName_referenceField_rankedAfterAssignableFieldWhenExpectedTypeKnown() {
    final var items =
        fixture.complete(
            """
            class Test {
                String text = "";
                Object object = new Object();

                void m() {
                    String s = §
                }
            }""");
    assertThat(labels(items)).contains("text", "object");
    final var textItem = itemWithFilterText(items, "text").orElseThrow();
    final var objectItem = itemWithFilterText(items, "object").orElseThrow();
    assertThat(textItem.getSortText()).isLessThan(objectItem.getSortText());
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
  void simpleName_expressionContext_voidMethodsExcluded() {
    final var items =
        labels(
            fixture.complete(
                """
                class Test {
                    void consume(String s) {}
                    String result() { return ""; }
                    void m() {
                        consume(§result());
                    }
                }"""));
    assertThat(items).contains("result");
    assertThat(items).doesNotContain("wait", "finalize", "notify", "notifyAll");
  }

  // ── string literal / bare dot / no-op positions ──────────────────────────────

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

  @Test
  void simpleName_switchCaseLabel_suggestsEnumConstants() {
    final var items =
        labels(
            fixture.complete(
                """
                class Test {
                    enum Type {
                        RESOURCE_METHOD,
                        SUB_RESOURCE_LOCATOR
                    }

                    void m(Type type) {
                        switch (type) {
                            case §
                        }
                    }
                }"""));

    assertThat(items).contains("RESOURCE_METHOD", "SUB_RESOURCE_LOCATOR");
  }

  // CQ-0021
  @Test
  void simpleName_switchCaseLabel_typePatternSubject_suggestsTypes() {
    final var items =
        labels(
            fixture.complete(
                """
                class Test {
                    void m(Object obj) {
                        switch (obj) {
                            case §
                        }
                    }
                }"""));

    assertThat(items).contains("String", "Integer");
    assertThat(items).doesNotContain("obj");
  }

  @Test
  void simpleName_nestedClass_suggestsNestedClass() {
    final var items =
        labels(
            fixture.complete(
                """
                class Test {
                    void m() {
                        Com§
                    }

                    private static class ComponentLoggingListener {}
                }"""));

    assertThat(items).contains("ComponentLoggingListener");
  }

  @Test
  void throwStatement_simpleName_ranksThrowablesHigher() {
    final var items =
        fixture.complete(
            """
            class Test {
                void m() {
                    throw §
                }
                IllegalArgumentException getException() { return new IllegalArgumentException(); }
                String getStr() { return ""; }
            }""");
    assertThat(labels(items)).contains("getException", "getStr");
    final var exceptionItem = itemWithFilterText(items, "getException").orElseThrow();
    final var strItem = itemWithFilterText(items, "getStr").orElseThrow();
    assertThat(exceptionItem.getSortText()).isLessThan(strItem.getSortText());
  }

  @Test
  void throwStatement_constructorCall_ranksThrowablesHigher() {
    final var items =
        labels(
            fixture.complete(
                """
                class Test {
                    void m() {
                        throw new §
                    }
                }"""));
    assertThat(items).contains("IllegalArgumentException", "RuntimeException");
    assertThat(items).doesNotContain("String", "StringBuilder");
  }

  @Test
  void precedence_localsFieldsMethodsMatchesPriority() {
    final var items =
        fixture.complete(
            """
            class Test {
                String fieldString;
                String methodString() { return ""; }
                void m(String paramString) {
                    String x = §
                }
            }""");
    assertThat(labels(items)).contains("paramString", "fieldString", "methodString");
    final var paramItem = itemWithFilterText(items, "paramString").orElseThrow();
    final var fieldItem = itemWithFilterText(items, "fieldString").orElseThrow();
    final var methodItem = itemWithFilterText(items, "methodString").orElseThrow();

    assertThat(paramItem.getSortText()).isLessThan(fieldItem.getSortText());
    assertThat(fieldItem.getSortText()).isLessThan(methodItem.getSortText());
  }

  // ── lambda scope ─────────────────────────────────────────────────────────────

  @Test
  void simpleName_lambdaParam_notVisibleAfterLambdaCloses() {
    final var labels =
        labels(
            fixture.complete(
                """
                class Test {
                    void m() {
                        java.util.List.of(1, 2).forEach(n -> {
                        });
                        §
                    }
                }"""));

    assertThat(labels).doesNotContain("n");
  }

  @Test
  void simpleName_lambdaBodyLocal_notVisibleAfterLambdaCloses() {
    final var labels =
        labels(
            fixture.complete(
                """
                class Test {
                    void m() {
                        java.util.List.of(1, 2).forEach(n -> {
                            int doubled = n * 2;
                        });
                        §
                    }
                }"""));

    assertThat(labels).doesNotContain("doubled");
  }

  @Test
  void simpleName_methodLocal_visibleAfterNestedLambdaCloses() {
    final var labels =
        labels(
            fixture.complete(
                """
                class Test {
                    void m() {
                        int count = 0;
                        java.util.List.of(1, 2).forEach(n -> {
                            int inner = n * 2;
                        });
                        §
                    }
                }"""));

    assertThat(labels).contains("count");
    assertThat(labels).doesNotContain("n", "inner");
  }

  // ── type-index gating ────────────────────────────────────────────────────────

  @Test
  void simpleName_uppercasePrefix_scopeCandidateRanksBeforeTypeIndex() throws Exception {
    localFixture =
        new CompletionFixture(
            CompletionFixture.typeIndex(
                tmp.resolve("index.json"),
                CompletionFixture.typeEntry("Random", "java.util.Random", TypeKind.CLASS)));

    final var labels =
        labels(
            localFixture.complete(
                """
                class Test {
                    static final Object RESOURCE = new Object();
                    void m() {
                        R§
                    }
                }"""));

    assertThat(labels).contains("RESOURCE", "Random");
    assertThat(labels.indexOf("RESOURCE")).isLessThan(labels.indexOf("Random"));
  }

  // ── presentation details ─────────────────────────────────────────────────────
}
