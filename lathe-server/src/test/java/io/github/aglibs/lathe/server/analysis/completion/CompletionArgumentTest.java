package io.github.aglibs.lathe.server.analysis.completion;

import static io.github.aglibs.lathe.server.analysis.completion.CompletionResultAssert.assertThatCompletion;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.typeindex.TypeKind;
import java.io.IOException;
import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.Test;

class CompletionArgumentTest extends CompletionTestSupport {

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
  void argumentPosition_zeroParamMethodInsideChain_suppressesCompletions() {
    assertThat(
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
                    Child() {
                        super(
                            builder()
                                .value(getStr())
                                .build(§));
                    }
                }"""))
        .isEmpty();
  }

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
}
