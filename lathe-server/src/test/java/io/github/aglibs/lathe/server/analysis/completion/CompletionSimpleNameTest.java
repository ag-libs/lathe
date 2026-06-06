package io.github.aglibs.lathe.server.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.Test;

class CompletionSimpleNameTest extends CompletionTestSupport {

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
  void simpleName_innerClassMethod_localsVisible() {
    // Locals declared in an inner class method must appear at usage sites within that method
    final var items =
        labels(
            fixture.complete(
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
                }"""));
    assertThat(items).contains("localVar");
  }

  @Test
  void simpleName_switchCaseLocal_visibleAtUsageSite() {
    // Local declared inside a switch-case arm must appear in completions at the usage site
    final var items =
        labels(
            fixture.complete(
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
                }"""));
    assertThat(items).contains("result");
  }

  @Test
  void simpleName_switchCaseLocal_forLoopNestedInSwitch() {
    // Local declared inside for-loop inside switch-case arm must be visible
    final var items =
        labels(
            fixture.complete(
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
                }"""));
    assertThat(items).contains("item");
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
    assertThat(items).contains("getFoo");
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
}
