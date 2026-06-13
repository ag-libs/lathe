package io.github.aglibs.lathe.server.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class CompletionMemberAccessTest extends CompletionTestSupport {

  private static void assertLabelBefore(
      final List<String> labels, final String earlier, final String later) {
    assertThat(labels).contains(earlier, later);
    assertThat(labels.indexOf(earlier)).isLessThan(labels.indexOf(later));
  }

  private static List<String> completeCollectorsInReturn(
      final String imports, final String returnType, final String streamType) {
    return labels(
        fixture.complete(
            """
            %s
            import java.util.stream.Collectors;
            import java.util.stream.Stream;
            class Test {
                %s value(Stream<%s> stream) {
                    return stream.collect(Collectors.§)
                }
            }"""
                .formatted(imports, returnType, streamType)));
  }

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
  void memberAccess_inIncompleteAssignment_doesNotLeakSimpleNameCandidates() {
    final var items =
        fixture.complete(
            """
            class Test {
                static class Event {
                    String getResourceModel() { return ""; }
                }

                java.util.List<String> resources;

                void onEvent(Event event) {
                    resources = event.§
                }
            }""");

    assertThat(labels(items))
        .contains("getResourceModel")
        .doesNotContain("resources", "new", "this");
  }

  @Test
  void memberAccess_afterAssignmentStatement_remainsMemberAccess() {
    final String source =
        """
        class Test {
            static final Logger LOGGER = new Logger();
            java.util.List<String> resources;

            void onEvent(Event event) {
                resources = event.resources();
                LOGGER.de§
                resources = event.resources();
            }

            static class Event {
                java.util.List<String> resources() { return java.util.List.of(); }
            }

            static class Logger {
                void debug(String message) {}
            }
        }""";
    final var items = fixture.complete(source);

    assertThat(labels(items)).contains("debug");
    assertThat(labels(items)).doesNotContain("resources");
  }

  @Test
  void memberAccess_staticReceiver_booleanExpectedType_ranksBooleanMembersFirst() {
    final var items =
        fixture.complete(
            """
            class Test {
                static class Providers {
                    static boolean isProvider(Class<?> type) { return true; }
                    static boolean isSupportedContract(Class<?> type) { return true; }
                    static void ensureContract(Class<?> type) {}
                    static Iterable<Object> getProviders() { return null; }
                }

                void m() {
                    boolean supported = Providers.§
                }
            }""");

    assertThat(items.getFirst().getFilterText()).isEqualTo("isProvider");
    assertThat(labels(items)).contains("isSupportedContract");
    assertThat(labels(items)).doesNotContain("ensureContract");
    assertThat(itemWithFilterText(items, "isProvider").orElseThrow().getSortText())
        .isLessThan(itemWithFilterText(items, "getProviders").orElseThrow().getSortText());
  }

  @Test
  void memberAccess_instanceReceiver_booleanExpectedType_ranksBooleanMembersFirst() {
    final var items =
        fixture.complete(
            """
            class Test {
                static class Provider {
                    boolean isProvider() { return true; }
                    void ensureContract() {}
                    String getName() { return ""; }
                }

                void m(Provider provider) {
                    boolean supported = provider.§
                }
            }""");

    assertThat(items.getFirst().getFilterText()).isEqualTo("isProvider");
    assertThat(labels(items)).doesNotContain("ensureContract");
    assertThat(itemWithFilterText(items, "isProvider").orElseThrow().getSortText())
        .isLessThan(itemWithFilterText(items, "getName").orElseThrow().getSortText());
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
        itemWithFilterText(items, "size").map(CompletionItem::getSortText).orElseThrow();
    assertThat(items).anyMatch(i -> "wait".equals(i.getFilterText()));
    assertThat(items).anyMatch(i -> "notify".equals(i.getFilterText()));
    assertThat(items).anyMatch(i -> "notifyAll".equals(i.getFilterText()));
    items.stream()
        .filter(
            i ->
                "wait".equals(i.getFilterText())
                    || "notify".equals(i.getFilterText())
                    || "notifyAll".equals(i.getFilterText()))
        .forEach(i -> assertThat(i.getSortText()).isGreaterThan(sizeSort));
  }

  @Test
  void memberAccess_typeArgResolved_notRawTypeVar() {
    final var items =
        fixture.complete(
            """
            class Test {
                void m(java.util.List<String> list) {
                    list.add§
                }
            }""");
    assertThat(items)
        .anySatisfy(
            i -> {
              assertThat(i.getLabel()).isEqualTo("add");
              assertThat(i.getLabelDetails()).isNotNull();
              assertThat(i.getLabelDetails().getDetail()).isEqualTo("(String)");
            });
    assertThat(items)
        .noneSatisfy(
            i -> {
              assertThat(i.getLabel()).isEqualTo("add");
              assertThat(i.getLabelDetails()).isNotNull();
              assertThat(i.getLabelDetails().getDetail()).isEqualTo("(E)");
            });
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
  void memberAccess_nestedEnumReceiver_usesConstantLabels() {
    final List<String> items =
        labels(
            fixture.complete(
                """
                class Event {
                    enum Type {
                        INITIALIZED,
                        IDLE
                    }
                }

                class Test {
                    void m() {
                        Event.Type.I§
                    }
                }"""));

    assertThat(items).contains("INITIALIZED", "IDLE").doesNotContain("Type.INITIALIZED");
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
    assertThat(labels(items)).contains("size", "equals");
    final var sizeItem = itemWithFilterText(items, "size").orElseThrow();
    final var equalsItem =
        items.stream().filter(i -> "equals".equals(i.getFilterText())).findFirst().orElseThrow();
    assertThat(sizeItem.getSortText()).isLessThan(equalsItem.getSortText());
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
  void memberAccess_typeReceiver_suggestsStaticNestedClass() {
    final var items =
        labels(
            fixture.complete(
                """
                class Test {
                    private static class ComponentLoggingListener {}

                    void m() {
                        Test.Com§
                    }
                }"""));

    assertThat(items).contains("ComponentLoggingListener");
  }

  // CQ-0020
  @Test
  void memberAccess_staticReceiverInArgumentPosition_rankedByOuterArgumentExpectedType() {
    final var items =
        fixture.complete(
            """
            class Test {
                static class Factory {
                    static Factory create() { return new Factory(); }
                    static String label() { return ""; }
                    static int count() { return 0; }
                }

                void consume(Factory f) {}

                void m() {
                    consume(Factory.§)
                }
            }""");

    assertThat(itemWithFilterText(items, "create").orElseThrow().getSortText())
        .isLessThan(itemWithFilterText(items, "label").orElseThrow().getSortText());
    assertThat(itemWithFilterText(items, "create").orElseThrow().getSortText())
        .isLessThan(itemWithFilterText(items, "count").orElseThrow().getSortText());
  }

  @Test
  void memberAccess_collectorsReceiverInsideCollectArgument_suggestsCollectorMethods() {
    final var items =
        labels(
            fixture.complete(
                """
                import java.util.List;
                import java.util.stream.Collectors;
                import java.util.stream.Stream;
                class Test {
                    void m(Stream<String> stream) {
                        List<String> ps = stream.collect(Collectors.§)
                    }
                }"""));

    assertThat(items).contains("toList", "groupingBy", "joining");
  }

  // CQ-0027
  @Test
  @Disabled("CQ-0027: Collectors member completion is not yet ranked by enclosing return type")
  void memberAccess_collectorsReceiverInsideReturnCollect_rankedByReturnType() {
    final var stringItems = completeCollectorsInReturn("", "String", "String");
    assertLabelBefore(stringItems, "joining", "averagingDouble");
    assertLabelBefore(stringItems, "joining", "groupingBy");

    final var mapItems =
        completeCollectorsInReturn(
            "import java.util.Map;", "Map<String, String>", "Map.Entry<String, String>");
    assertLabelBefore(mapItems, "toMap", "toList");
    assertLabelBefore(mapItems, "toMap", "joining");

    final var listItems =
        completeCollectorsInReturn("import java.util.List;", "List<String>", "String");
    assertLabelBefore(listItems, "toList", "groupingBy");
    assertLabelBefore(listItems, "toList", "toMap");
  }

  @Test
  void memberAccess_typeReceiver_excludesInstanceMembers() {
    final var items =
        labels(
            fixture.complete(
                """
                class Test {
                    String instanceField = "";
                    void instanceMethod() {}

                    static String staticField = "";
                    static void staticMethod() {}

                    void m() {
                        Test.§
                    }
                }"""));

    assertThat(items)
        .contains("staticField", "staticMethod")
        .doesNotContain("instanceField", "instanceMethod");
  }
}
