package io.github.aglibs.lathe.server.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

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
    assertThat(items.getFirst().getLabel()).isEqualTo("Listener");
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
    assertThat(items.getFirst().getLabel()).isEqualTo("Listener");
  }

  @Test
  void constructorCall_typePrefix_suggestsJavaLangType_withoutTypeIndex() {
    localFixture = new CompletionFixture();
    assertThat(labels(localFixture.complete("class Test { void m() { Object o = new Str§ } }")))
        .contains("String");
  }

  // ── type index: unimported simple name fallback (gap B) ───────────────────────
}
