package io.github.aglibs.lathe.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.Test;

class CompletionTypeReferenceTest extends CompletionTestSupport {

  @Test
  void typeReference_variableInitializer_uppercasePrefixSuggestsTypes() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        void m() {
                            Object x = M§
                        }
                    }""")))
        .contains("Math");
  }

  @Test
  void typeReference_methodArgument_uppercasePrefixSuggestsTypes() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        void accept(Object o) {}
                        void m() {
                            accept(M§);
                        }
                    }""")))
        .contains("Math");
  }

  @Test
  void typeReference_constructorCall_innerClassFromSameFile() {
    // Inner classes defined in the same compilation unit should appear for new I§
    final var items =
        labels(
            fixture.complete(
                """
                class Test {
                    static class Inner {}
                    static class IrrelevantOther {}
                    void m() {
                        new I§
                    }
                }"""));
    assertThat(items).contains("Inner");
  }

  @Test
  void typeReference_constructorCall_privateStaticInnerClass() {
    // Private inner classes are still valid in new§ within the same top-level class
    final var items =
        labels(
            fixture.complete(
                """
                class Outer {
                    private static class Builder {}
                    void m() {
                        new B§
                    }
                }"""));
    assertThat(items).contains("Builder");
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
        .contains("Runnable");
    assertThat(labels(fixture.complete("class Test { void m() { Object value = new TimeU§ } }")))
        .doesNotContain("TimeUnit");
  }

  @Test
  void methodBody_afterNew_suggestsInFileInterfaceForAnonymousClass() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        interface XListener {
                            void event();
                        }
                        void m() {
                            Object value = new XL§
                        }
                    }""")))
        .contains("XListener");
  }

  @Test
  void typeReference_inFileTypesNotInScope_writtenQualifiedOrLeftOut() {
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        void other() {
                            class LocalHelper {}
                        }
                        void m() {
                            Local§
                        }
                    }""")))
        .doesNotContain("LocalHelper");
    assertThat(
            insertTextOf(
                fixture.complete(
                    """
                    class Test {
                        void m() {
                            HelperOf§
                        }
                    }
                    class Other {
                        static class HelperOfOther {}
                    }"""),
                "HelperOfOther"))
        .isEqualTo("Other.HelperOfOther");
  }

  @Test
  void typeReference_dottedOuterClass_innerTypeSuggested() {
    assertThat(labels(fixture.complete("class Test { void m(java.util.Map.En§ entry) {} }")))
        .anyMatch(l -> l.startsWith("Entry"));
  }

  @Test
  void typeReference_typeDot_suggestsClassLiteral() {
    assertThat(labels(fixture.complete("class Test { void m() { Object.§ } }"))).contains("class");
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

  // A type argument, a wildcard's bound and a type parameter's bound take any reference type,
  // whatever the clause around them filters (interfaces after implements, classes after extends):
  // the type being declared (Comparable<Self>) and the file's other types included.
  @Test
  void typeArgument_anyPosition_suggestsAnyReferenceType() {
    assertThat(labels(fixture.complete("class Test implements Comparable<Str§> {}")))
        .contains("String");
    assertThat(labels(fixture.complete("record Test() implements Comparable<Str§> {}")))
        .contains("String");
    assertThat(labels(fixture.complete("interface Test extends Comparable<Str§> {}")))
        .contains("String");
    assertThat(labels(fixture.complete("class Test extends java.util.ArrayList<Runn§> {}")))
        .contains("Runnable");
    assertThat(labels(fixture.complete("class Test implements Comparable<? super Str§> {}")))
        .contains("String");
    assertThat(labels(fixture.complete("class Box<T extends Runn§> {}"))).contains("Runnable");
    assertThat(labels(fixture.complete("class Test implements Comparable<Te§> {}")))
        .contains("Test");
    assertThat(
            labels(
                fixture.complete("class Test { static class Inner {} java.util.List<Inn§> x; }")))
        .contains("Inner");
  }

  // None of those positions can hold a keyword, a primitive or a member declaration.
  @Test
  void typeArgument_anyPosition_offersNoKeywordPrimitiveOrOverride() {
    for (final String source :
        List.of(
            "class Test implements Comparable<i§> {}",
            "class Test { void m() { class L implements Comparable<i§> {} } }",
            "class Test { java.util.List<i§> x; }",
            "class Test { void m() { java.util.List<i§> x; } }",
            "class Test { java.util.List<? extends i§> x; }",
            "class Box<T extends i§> {}")) {
      assertThat(labels(fixture.complete(source)))
          .as(source)
          .contains("Integer")
          .doesNotContain("int", "interface");
    }

    final List<CompletionItem> headerItems =
        fixture.complete("class Test implements Comparable<§> {}");
    assertThat(labels(headerItems)).doesNotContain("public", "class", "void", "int");
    assertThat(headerItems)
        .noneMatch(
            item -> item.getInsertText() != null && item.getInsertText().contains("@Override"));
  }

  // An array's element type may be primitive (List<int[]>), and is still no member start.
  @Test
  void typeArgument_arrayElementType_suggestsPrimitivesOnly() {
    for (final String source :
        List.of(
            "class Test { java.util.List<i§[]> rows; }",
            "class Test { java.util.List<? extends i§[]> rows; }",
            "class Test { void m() { java.util.List<i§[][]> rows; } }")) {
      assertThat(labels(fixture.complete(source)))
          .as(source)
          .contains("int", "Integer")
          .doesNotContain("interface");
    }

    final List<CompletionItem> headerItems =
        fixture.complete("class Test implements Comparable<§[]> {}");
    assertThat(labels(headerItems)).contains("int").doesNotContain("public", "class", "void");
    assertThat(headerItems)
        .noneMatch(
            item -> item.getInsertText() != null && item.getInsertText().contains("@Override"));
  }

  // A type parameter's name declares a new name: nothing completes there.
  @Test
  void typeParameterName_declaredName_offersNothing() {
    assertThat(labels(fixture.complete("class Box<T§ extends Number> {}"))).isEmpty();
  }

  // ── simple name ───────────────────────────────────────────────────────────────

  @Test
  void constructorCall_expectedInterfaceArgument_ranksInterfaceFirst() {
    final var items =
        fixture.complete(
            """
            class Test {
                interface Listener {
                    default void started() {}
                }
                static class Builder {
                    void addListener(Listener listener) {}
                }
                void m(Builder builder) {
                    builder.addListener(new §);
                }
            }""");
    assertThat(items).isNotEmpty();
    assertThat(items.get(0).getLabel()).isEqualTo("Listener");
  }

  @Test
  void constructorCall_unqualifiedExpectedInterfaceArgument_ranksInterfaceFirst() {
    final var items =
        fixture.complete(
            """
            class Test {
                interface Listener {
                    default void started() {}
                }
                void addListener(Listener listener) {}
                void m() {
                    addListener(new §);
                }
            }""");
    assertThat(items).isNotEmpty();
    assertThat(items.get(0).getLabel()).isEqualTo("Listener");
  }

  @Test
  void constructorCall_typePrefix_suggestsJavaLangType_withoutTypeIndex() {
    localFixture = new CompletionFixture();
    assertThat(labels(localFixture.complete("class Test { void m() { Object o = new Str§ } }")))
        .contains("String");
  }

  // ── type index: unimported simple name fallback (gap B) ───────────────────────

  // ── a member's type ───────────────────────────────────────────────────────────

  // A record cannot declare an instance field, and javac's parser drops one: the type at the start
  // of an instance member must still complete, as it does in a class.
  @Test
  void memberType_inRecordBody_completesTheInstanceMemberType() {
    for (final String member :
        List.of("St§", "public St§", "public St§ helper() { return null; }")) {
      assertThat(
              labels(
                  fixture.complete(
                      """
                      record Test(int x) {
                          %s
                          void after() {}
                      }"""
                          .formatted(member))))
          .as(member)
          .contains("String");
    }
  }
}
