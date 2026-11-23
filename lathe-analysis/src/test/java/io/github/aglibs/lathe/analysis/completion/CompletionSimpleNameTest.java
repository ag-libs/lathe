package io.github.aglibs.lathe.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.typeindex.TypeKind;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class CompletionSimpleNameTest extends CompletionTestSupport {

  private static void assertLabelBefore(final List<String> labels) {
    assertThat(labels).contains("text", "fromConfig");
    assertThat(labels.indexOf("text")).isLessThan(labels.indexOf("fromConfig"));
  }

  // An anonymous class has no name: completion inside it, or of a type in a file holding one, must
  // still answer instead of failing on the empty name.
  @Test
  void simpleName_insideAnonymousClass_completesOuterMembers_fileWithOneCompletesTypes() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        int count;
                        Runnable r = new Runnable() {
                            public void run() { cou§ }
                        };
                    }""")))
        .contains("count");
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        Runnable r = new Runnable() { public void run() {} };
                        void m() { Object o = new Tes§ }
                    }""")))
        .contains("Test");
  }

  // An anonymous class in a method body sees its own method's locals and the enclosing method's
  // parameters and the locals declared before it, as Java scoping does.
  @Test
  void simpleName_insideAnonymousClassInMethod_completesItsOwnAndTheEnclosingLocals() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        void m() {
                            Runnable r = new Runnable() {
                                public void run() { String own = ""; String s = ow§; }
                            };
                        }
                    }""")))
        .contains("own");
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        void m(String duration) {
                            int before = 1;
                            Runnable r = new Runnable() {
                                public void run() { Object o = §; }
                            };
                            int after = 2;
                        }
                    }""")))
        .contains("duration", "before")
        .doesNotContain("after");
  }

  @Test
  void simpleName_afterAnonymousClassCloses_itsLocalsAreNotVisible() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        void m() {
                            Runnable a = new Runnable() { public void run() { int inner = 1; } };
                            int y = in§;
                        }
                    }""")))
        .doesNotContain("inner");
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        void m() {
                            Runnable a = new Runnable() { public void run() { int inner = 1; } };
                            Runnable b = new Runnable() {
                                public void run() { int inside = 1; int x = in§; }
                            };
                        }
                    }""")))
        .contains("inside")
        .doesNotContain("inner");
  }

  // A local record, enum or interface is static: the enclosing method's locals are out of its
  // reach.
  @Test
  void simpleName_insideLocalRecord_enclosingLocalsAreNotVisible() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        void m(String duration) {
                            String local = "";
                            record R(int x) {
                                String f() { String own = ""; return §; }
                            }
                        }
                    }""")))
        .contains("own")
        .doesNotContain("duration", "local");
  }

  // A block, a loop, an `if` or a `catch` that closed before the cursor takes its locals with it.
  @ParameterizedTest
  @ValueSource(
      strings = {
        "{ int hidden = 1; } hid§",
        "for (int hidden = 0; hidden < 3; hidden++) {} hid§",
        "for (String hidden : args) {} hid§",
        "if (args.length > 0) { int hidden = 1; } hid§",
        "try { } catch (RuntimeException hidden) { } hid§",
        "{ { int hidden = 1; } int y = hid§; }"
      })
  void simpleName_afterClosedBlock_itsLocalsAreNotVisible(final String body) {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        void m(String[] args) {
                            %s
                        }
                    }"""
                        .formatted(body))))
        .doesNotContain("hidden");
  }

  // A local of an old-style switch's earlier case and a local a pattern introduces after its
  // statement stay visible; and with a snapshot that predates the typed name, whose `}` of the
  // cursor's own block lies before the cursor, so do the block's.
  @Test
  void simpleName_insideOpenBlock_itsLocalsStayVisible() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        void m(int k) {
                            switch (k) {
                                case 1: int early = 1; break;
                                case 2: early = 2; int x = ear§;
                            }
                        }
                    }""")))
        .contains("early");
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        int m(Object o) {
                            if (!(o instanceof String text)) { return 0; }
                            return te§;
                        }
                    }""")))
        .contains("text");
    final var source =
        """
        class Test {
            void m() {
                for (int loopIdx = 0; loopIdx < 3; loopIdx++) {%s}
            }
        }""";
    assertThat(labels(fixture.completeWithCache(source.formatted(""), source.formatted("loopI§"))))
        .contains("loopIdx");
    final var edited =
        """
        class Test {
            void m() {
                int first = %s;
                int count = 2;
                int y = %s;
                int last = %s;
            }
        }""";
    assertThat(
            labels(
                fixture.completeWithCache(
                    edited.formatted("1", "0", "3"), edited.formatted("10", "cou§", "30"))))
        .contains("count");
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
  void simpleName_overwrittenConstructorCloseBrace_recoversEnclosingScope() {
    // Typing over the compact constructor's closing brace leaves the block unclosed. The
    // parser must still recover the enclosing constructor scope; a hard failure here returned zero
    // items (class=null method=null). The record component 'name' is in scope and must be offered.
    final var items =
        fixture.complete(
            """
            record Config(String name) {
                Config {
                    name = name.trim();
                    n§
            """);

    assertThat(labels(items)).contains("name");
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

    assertLabelBefore(items);
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

    assertLabelBefore(items);
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

  // CQ-0062: an inner class's annotated remove(Object) beside the outer list's. Java searches the
  // innermost class with a method named remove and no further out, so inside Inner only Inner's
  // overloads are callable by the simple name; remove(0) there binds to Inner.remove(Object).
  @Test
  void simpleName_innerClassDeclaresTheName_onlyItsOverloadsOffered() {
    final List<CompletionItem> items =
        fixture.complete(
            """
            import java.lang.annotation.*;
            @Target(ElementType.TYPE_USE) @interface Mark {}
            class Test extends java.util.ArrayList<String> {
                class Inner {
                    void remove(@Mark Object value) {}
                    void remove(int index) {}
                    void run() { remove§(""); }
                }
            }""");

    assertThat(items)
        .filteredOn(item -> item.getLabel().equals("remove"))
        .extracting(CompletionItem::getDetail)
        .containsExactlyInAnyOrder(
            "Inner.remove(Object value) : void", "Inner.remove(int index) : void");
  }

  // An inner class's add(@Mark String) beside the enclosing class's inherited add(E), shown as
  // add(String): the inner class has a method named add, so the enclosing class's are not callable
  // there by the simple name.
  @Test
  void simpleName_innerMethodBesideInheritedGenericOne_onlyTheInnerOffered() {
    final List<CompletionItem> items =
        fixture.complete(
            """
            import java.lang.annotation.*;
            @Target(ElementType.TYPE_USE) @interface Mark {}
            class Test extends java.util.ArrayList<String> {
                class Inner {
                    void add(@Mark String value) {}
                    void run() { add§(""); }
                }
            }""");

    assertThat(items)
        .filteredOn(item -> item.getLabel().equals("add"))
        .extracting(CompletionItem::getDetail)
        .containsExactly("Inner.add(String value) : void");
  }

  // An override of a generic superclass's method, add(String) over ArrayList<String>'s add(E), is
  // one
  // method: getAllMembers leaves out what a member overrides.
  @Test
  void simpleName_overrideOfGenericSuperclassMethod_offeredOnce() {
    final List<CompletionItem> items =
        fixture.complete(
            """
            import java.lang.annotation.*;
            @Target(ElementType.TYPE_USE) @interface Mark {}
            class Test extends java.util.ArrayList<String> {
                @Override public boolean add(@Mark String value) { return super.add(value); }
                void run() { add§(""); }
            }""");

    assertThat(items)
        .filteredOn(item -> item.getLabel().equals("add"))
        .extracting(CompletionItem::getDetail)
        .filteredOn(detail -> !detail.contains(","))
        .containsExactly("Test.add(String value) : boolean");
  }

  // A statically imported method is hidden by a method of that name in the class, as javac binds
  // send(...) there to the class's own.
  @Test
  void simpleName_classDeclaresTheName_staticImportOfItHidden() {
    final List<CompletionItem> items =
        fixture.complete(
            """
            package example;
            import static example.Words.send;
            class Words { static void send(String value) {} }
            class Test {
                void send(int count) {}
                void run() { send§(1); }
            }
            """);

    assertThat(items)
        .filteredOn(item -> item.getLabel().equals("send"))
        .extracting(CompletionItem::getDetail)
        .containsExactly("Test.send(int count) : void");
  }

  // The same method reached by a single and a wildcard static import is one method.
  @Test
  void simpleName_sameMethodImportedTwice_offeredOnce() {
    final List<CompletionItem> items =
        fixture.complete(
            """
            package example;
            import static example.Words.send;
            import static example.Words.*;
            class Words { static void send(String value) {} }
            class Test { void run() { send§(""); } }
            """);

    assertThat(items)
        .filteredOn(item -> item.getLabel().equals("send"))
        .extracting(CompletionItem::getDetail)
        .containsExactly("Words.send(String value) : void");
  }

  // Two imported overloads one of which mentions a missing type where it is not a type argument of
  // the parameter: in the type it is nested in, or in a type variable's bound. Whether they are the
  // same is uncertain, so both stay.
  @ParameterizedTest
  @ValueSource(
      strings = {
        "static void send(Holder<Missing>.Entry entry) {}|static void send(Holder<String>.Entry entry) {}",
        "static <T extends Missing> void send(T value) {}|static <T extends String> void send(T value) {}"
      })
  void simpleName_importedOverloadMentionsAMissingTypeDeeply_keepsBoth(final String methods) {
    final String[] declared = methods.split("\\|");
    final List<CompletionItem> items =
        fixture.complete(
            """
            package example;
            import static example.Others.send;
            import static example.Words.send;
            class Holder<T> { class Entry {} }
            class Others { %s }
            class Words { %s }
            class Test { void run() { send§(null); } }
            """
                .formatted(declared[0], declared[1]));

    assertThat(items)
        .filteredOn(item -> item.getLabel().equals("send"))
        .extracting(CompletionItem::getDetail)
        .hasSize(2)
        .anyMatch(detail -> detail.startsWith("Others.send"))
        .anyMatch(detail -> detail.startsWith("Words.send"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Integer", "Missing"})
  void simpleName_staticImportsWithDifferentTypeArguments_keepsBothMethods(final String otherType) {
    final List<CompletionItem> items =
        fixture.complete(
            """
            package example;
            import java.util.List;
            import static example.Others.send;
            import static example.Words.send;
            class Others { static void send(List<%s> values) {} }
            class Words { static void send(List<String> values) {} }
            class Test { void run() { send§(List.of("word")); } }
            """
                .formatted(otherType));

    assertThat(items)
        .filteredOn(item -> item.getLabel().equals("send"))
        .extracting(CompletionItem::getDetail)
        .containsExactlyInAnyOrder(
            "Others.send(List<%s> values) : void".formatted(otherType),
            "Words.send(List<String> values) : void");
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

  @Test
  void simpleName_switchCaseLabelWhileTyping_suggestsEnumConstantsOfTheSelectorOnly() {
    final var source =
        """
        import java.util.concurrent.TimeUnit;
        class Test {
            void m(%s value) {
                switch (value) {
                    %s
                }
            }
        }""";
    final Function<String, List<String>> typing =
        typed ->
            labels(
                fixture.completeWithCache(
                    source.formatted("TimeUnit", ""), source.formatted("TimeUnit", typed)));

    assertThat(typing.apply("case §"))
        .containsExactlyInAnyOrder(
            "DAYS", "HOURS", "MICROSECONDS", "MILLISECONDS", "MINUTES", "NANOSECONDS", "SECONDS");
    assertThat(typing.apply("case MIL§")).containsExactly("MILLISECONDS");
    assertThat(
            labels(
                fixture.completeWithCache(
                    source.formatted("String", ""), source.formatted("String", "case §"))))
        .isEmpty();
  }

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
  void simpleName_nestedClasses_membersOfTheClassAtTheCursorAndItsEnclosingInstance() {
    final String sameNames =
        """
        class Test {
            static class B {
                static class Box {
                    int beta;
                    void m() {
                        %s§
                    }
                }
            }
            static class A { static class Box { int alpha; } }
        }""";
    final String outerField =
        """
        class Test {
            int outerField;
            %s class Nested {
                void m() {
                    outer§
                }
            }
        }""";

    assertThat(labels(fixture.complete(sameNames.formatted("be")))).contains("beta");
    assertThat(labels(fixture.complete(sameNames.formatted("al")))).doesNotContain("alpha");
    assertThat(labels(fixture.complete(outerField.formatted("")))).contains("outerField");
    assertThat(labels(fixture.complete(outerField.formatted("static"))))
        .doesNotContain("outerField");
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

  // The thrown value must be a Throwable; the arguments that build it take any value their
  // parameter does, so a message completes as it would outside the throw.
  @Test
  void throwStatement_constructorArgument_completesValuesNotOnlyThrowables() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        void m(String duration) {
                            throw new IllegalArgumentException("bad: " + du§);
                        }
                    }""")))
        .contains("duration");
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        void m(String duration) {
                            throw new IllegalArgumentException(du§);
                        }
                    }""")))
        .contains("duration");
  }

  @Test
  void throwStatement_newInsideConstructorArgument_completesAnyType() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        void m() {
                            throw new IllegalStateException(new StringBu§);
                        }
                    }""")))
        .contains("StringBuilder");
  }

  @Test
  void throwStatement_newInsideThrownCallArgument_completesAnyType() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        static RuntimeException wrap(Object o) { return new RuntimeException(); }
                        void m() {
                            throw wrap(new StringBu§);
                        }
                    }""")))
        .contains("StringBuilder");
  }

  @Test
  void throwStatement_callInsideConstructorArgument_completesTheMethod() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        static RuntimeException wrap(Throwable t) { return new RuntimeException(t); }
                        void m(Exception cause) {
                            throw new IllegalStateException(Test.wr§(cause));
                        }
                    }""")))
        .contains("wrap");
  }

  @Test
  void throwStatement_staticCallReturningAThrowable_completesTheMethod() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        static RuntimeException wrap(Throwable t) { return new RuntimeException(t); }
                        void m(Exception cause) {
                            throw Test.wr§(cause);
                        }
                    }""")))
        .contains("wrap");
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
