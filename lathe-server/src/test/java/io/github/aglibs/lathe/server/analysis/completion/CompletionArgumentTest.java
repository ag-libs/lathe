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
    assertThat(labels(items)).contains("strVar", "intVar");
    final var strVarItem = itemWithFilterText(items, "strVar").orElseThrow();
    final var intVarItem = itemWithFilterText(items, "intVar").orElseThrow();
    assertThat(strVarItem.getSortText()).isLessThan(intVarItem.getSortText());
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
    assertThat(labels(items)).contains("strVar", "intVar");
    final var strVarItem = itemWithFilterText(items, "strVar").orElseThrow();
    final var intVarItem = itemWithFilterText(items, "intVar").orElseThrow();
    assertThat(strVarItem.getSortText()).isLessThan(intVarItem.getSortText());
  }

  @Test
  void argumentPosition_referenceLocal_rankedAfterAssignableLocal() {
    final var items =
        fixture.complete(
            """
            class Test {
                void accept(String s) {}
                void m() {
                    StringBuilder sb = new StringBuilder();
                    String text = "";
                    accept(§);
                }
            }""");
    assertThat(labels(items)).contains("text", "sb");
    final var textItem = itemWithFilterText(items, "text").orElseThrow();
    final var sbItem = itemWithFilterText(items, "sb").orElseThrow();
    assertThat(textItem.getSortText()).isLessThan(sbItem.getSortText());
  }

  @Test
  void argumentPosition_referenceLocal_rankedAfterAssignableLocalInReceiverQualifiedCall() {
    final var items =
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
            }""");
    assertThat(labels(items)).contains("text", "sb");
    final var textItem = itemWithFilterText(items, "text").orElseThrow();
    final var sbItem = itemWithFilterText(items, "sb").orElseThrow();
    assertThat(textItem.getSortText()).isLessThan(sbItem.getSortText());
  }

  @Test
  void argumentPosition_chainReceiverLocal_visibleWhenItCanProduceExpectedType() {
    final var items = fixture.complete(chainReceiverSource("accept(§);"));

    assertThat(labels(items)).contains("factory", "target");
    final var factoryItem = itemWithFilterText(items, "factory").orElseThrow();
    final var targetItem = itemWithFilterText(items, "target").orElseThrow();
    assertThat(targetItem.getSortText()).isLessThan(factoryItem.getSortText());

    assertThat(labels(fixture.complete(chainReceiverSource("accept(f§);")))).contains("factory");
  }

  private static String chainReceiverSource(final String callLine) {
    return """
        class Test {
            static class Target {}

            static class Factory {
                Target create() {
                    return new Target();
                }
            }

            void accept(Target target) {}

            void m() {
                Factory factory = new Factory();
                Target target = new Target();
                %s
            }
        }"""
        .formatted(callLine);
  }

  @Test
  void argumentPosition_referenceTypeParam_booleansDemoted() {
    final var items =
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
            }""");
    assertThat(labels(items)).contains("getFoo", "isReady").doesNotContain("true", "false");
    final var getFoo = itemWithFilterText(items, "getFoo").orElseThrow();
    final var isReady = itemWithFilterText(items, "isReady").orElseThrow();
    assertThat(getFoo.getSortText()).isLessThan(isReady.getSortText());
  }

  // ── constructor call position ─────────────────────────────────────────────────

  @Test
  void constructorArgument_memberAccess_booleanReturn_offeredAtBooleanSlot() {
    // Mirrors the method-call argument path, which already offers the boolean member. The
    // constructor-arg path falls through to the constructed/declared type as the expected value
    // (TypeResolver.resolveArgumentValueByPosition handles MethodInvocationTree but not
    // NewClassTree), and the asymmetric boolean-only filter (CQ-0043) then deletes the candidate.
    final var items =
        labels(
            fixture.complete(
                """
                class Config {
                    boolean isReady() { return true; }
                    String name() { return ""; }
                }
                class Service {
                    Service(boolean flag) {}
                }
                class Test {
                    void m() {
                        Config config = new Config();
                        final var svc = new Service(config.§);
                    }
                }"""));
    assertThat(items).contains("isReady", "name");
  }

  @Test
  void constructorArgument_booleanPrefix_popupNotEmpty() {
    final var items =
        labels(
            fixture.complete(
                """
                class Config {
                    boolean isReady() { return true; }
                }
                class Service {
                    Service(boolean flag) {}
                }
                class Test {
                    void m() {
                        Config config = new Config();
                        final var svc = new Service(config.isR§);
                    }
                }"""));
    assertThat(items).contains("isReady");
  }

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
    // no type-name noise in second arg position
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
    // directly assignable methods remain visible in constructor arguments
    final var firstArg =
        fixture.complete(
            """
            class Test {
                static class Receiver {
                    Receiver(String value) {}
                }

                String result() { return ""; }

                void m() {
                    new Receiver(§);
                }
            }""");
    assertThat(labels(firstArg)).contains("result");
  }

  @Test
  void constructorCallArgument_referenceLocal_rankedAfterAssignableLocal() {
    final var items =
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
            }""");
    assertThat(labels(items)).contains("text", "sb");
    final var textItem = itemWithFilterText(items, "text").orElseThrow();
    final var sbItem = itemWithFilterText(items, "sb").orElseThrow();
    assertThat(textItem.getSortText()).isLessThan(sbItem.getSortText());
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
    assertThat(items).contains("getStr");
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
        .containsLabel("sample")
        .doesNotContainLabel("count")
        .item("sample")
        .hasStaticImportEdit("example.StringSources.sample");
  }

  @Test
  void lambdaBody_mapReturnExpectedType_ranksStringHigher() {
    assertRanksStringHigherThanInt(
        fixture.complete(
            """
            class Test {
                void m(java.util.List<String> list) {
                    java.util.List<String> mapped = list.stream().map(s -> §).toList();
                }
                String getStr() { return ""; }
                int getInt() { return 0; }
            }"""));
  }

  @Test
  void lambdaBody_filterExpectedType_ranksBooleanHigher() {
    final var items =
        fixture.complete(
            """
            class Test {
                void m(java.util.List<String> list) {
                    list.stream().filter(s -> §);
                }
                boolean isReady() { return true; }
                String getStr() { return ""; }
            }""");
    assertThat(labels(items)).contains("isReady", "getStr");
    final CompletionItem readyItem = itemWithFilterText(items, "isReady").orElseThrow();
    final CompletionItem strItem = itemWithFilterText(items, "getStr").orElseThrow();
    assertThat(readyItem.getSortText()).isLessThan(strItem.getSortText());
  }

  @Test
  void lambdaBody_optionalMapExpectedType_ranksStringHigher() {
    assertRanksStringHigherThanInt(
        fixture.complete(
            """
            class Test {
                void m(java.util.Optional<String> opt) {
                    java.util.Optional<String> mapped = opt.map(s -> §);
                }
                String getStr() { return ""; }
                int getInt() { return 0; }
            }"""));
  }

  @Test
  void lambdaBody_collectToListExpectedType_ranksStringHigher() {
    assertRanksStringHigherThanInt(
        fixture.complete(
            """
            class Test {
                void m(java.util.List<String> list) {
                    java.util.List<String> mapped = list.stream().map(s -> §).collect(java.util.stream.Collectors.toList());
                }
                String getStr() { return ""; }
                int getInt() { return 0; }
            }"""));
  }

  @Test
  void lambdaBody_blockReturnExpectedType_ranksStringHigher() {
    assertRanksStringHigherThanInt(
        fixture.complete(
            """
            class Test {
                void m(java.util.List<String> list) {
                    java.util.List<String> mapped = list.stream().map(s -> {
                        return §
                    }).toList();
                }
                String getStr() { return ""; }
                int getInt() { return 0; }
            }"""));
  }

  @Test
  void lambdaBody_methodReturnExpectedType_ranksStringHigher() {
    assertRanksStringHigherThanInt(
        fixture.complete(
            """
            class Test {
                java.util.List<String> m(java.util.List<String> list) {
                    return list.stream().map(s -> §).toList();
                }
                String getStr() { return ""; }
                int getInt() { return 0; }
            }"""));
  }

  private void assertRanksStringHigherThanInt(final List<CompletionItem> items) {
    assertThat(labels(items)).contains("getStr", "getInt");
    final CompletionItem strItem = itemWithFilterText(items, "getStr").orElseThrow();
    final CompletionItem intItem = itemWithFilterText(items, "getInt").orElseThrow();
    assertThat(strItem.getSortText()).isLessThan(intItem.getSortText());
  }
}
