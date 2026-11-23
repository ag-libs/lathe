package io.github.aglibs.lathe.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
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
    assertThat(
            labels(
                fixture.complete(
                    "class Test { Object m() { return java.util.Collections.<Str§>emptyList(); } }")))
        .contains("String");
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        static class Inner {}
                        <T> T id(T t) { return t; }
                        Object m() { return this.<Inn§>id(null); }
                    }""")))
        .contains("Inner");
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        static class Foo { <T> Foo() {} }
                        Object m() { return new <Str§>Foo(); }
                    }""")))
        .contains("String");
    assertThat(
            labels(
                fixture.complete(
                    """
                    class Test {
                        java.util.function.Function<Object, String> f = String::<Str§>valueOf;
                    }""")))
        .contains("String");
    assertThat(
            labels(
                fixture.complete(
                    "class Test<P> { Object m() { return java.util.Collections.<P§>emptyList(); } }")))
        .contains("P");
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
            "class Box<T extends i§> {}",
            "class Test { Object m() { return java.util.Collections.<i§>emptyList(); } }",
            "class Test { <T> T id(T t) { return t; } Object m() { return this.<i§>id(null); } }",
            "class Test { static class Foo { <T> Foo() {} } Object m() { return new <i§>Foo(); } }",
            "class Test { java.util.function.Function<Object, String> f = String::<i§>valueOf; }")) {
      assertThat(labels(fixture.complete(source)))
          .as(source)
          .contains("Integer")
          .doesNotContain("int", "interface", "if", "instanceof");
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

  // A class's and a method's type variables are types wherever a type is written, at the start of
  // a statement that becomes a declaration, and in an anonymous class within the method.
  @Test
  void typeVariable_inScope_suggestedAsType() {
    assertThat(fixture.complete("class Box<Item> { It§ x; }"))
        .anySatisfy(
            item -> {
              assertThat(item.getLabel()).isEqualTo("Item");
              assertThat(item.getKind()).isEqualTo(CompletionItemKind.TypeParameter);
            });
    for (final String source :
        List.of(
            "class Box<Item> { void m() { It§ } }",
            "class Box<Item> { void m(int x) { switch (x) { case 1: It§ } } }",
            "class Box<Item> { void m(It§ p) {} }",
            "class Box<Item> { Object m(Object o) { return (It§) o; } }",
            "class Box<Item> { java.util.List<It§> items; }",
            "record Box<Item>(It§ a) {}",
            "class Box { <Item> void m() { new Object() { It§ x; }; } }",
            "interface Box<Item> { It§ get(); }")) {
      assertThat(labels(fixture.complete(source))).as(source).contains("Item");
    }
  }

  // A static member or nested type does not see the class's type variables, an interface's field
  // being static, nor does a local record see its method's. A type variable is no value, so
  // expressions and `new` leave it out, and neither a catch parameter, `throws`, a pattern, a
  // `permits` clause nor a switch rule's body can take one. It hides an outer type variable or a
  // type of the same name.
  @Test
  void typeVariable_staticContextOrExpression_notSuggested() {
    for (final String source :
        List.of(
            "class Box<Item> { static It§ x; }",
            "class Box<Item> { static void m() { It§ x; } }",
            "class Box<Item> { static class Inner { It§ x; } }",
            "class Box { <Item> void m() { record R() { static It§ x; } } }",
            "interface Box<Item> { It§ x = null; }",
            "class Box<Item> { Object m() { return It§.class; } }",
            "class Box<Item> { boolean m(Object o) { return o instanceof It§; } }",
            "class Box<Item> { void m() { Object o = It§ } }",
            "class Box<Item> { Object m() { return new It§ } }",
            "class Box<Item> { void m() { try {} catch (It§ e) {} } }",
            "class Box { <Item extends Exception> void m() throws It§ {} }",
            "class Box<Item> { void m(Object o) { if (o instanceof It§ i) {} } }",
            "sealed class Box<Item> permits It§ {}",
            "class Box<Item> { void m(int x) { switch (x) { case 1 -> It§; } } }")) {
      assertThat(labels(fixture.complete(source))).as(source).doesNotContain("Item");
    }

    assertThat(labels(fixture.complete("class Box<Item> { <Item> void m() { It§ x; } }")))
        .containsOnlyOnce("Item");
    assertThat(fixture.complete("class Box<String> { Str§ x; }"))
        .filteredOn(item -> item.getLabel().equals("String"))
        .singleElement()
        .extracting(CompletionItem::getKind)
        .isEqualTo(CompletionItemKind.TypeParameter);
  }

  // A type declared in the file completes at every type position, before the type index has it,
  // and as a supertype unless it is the declaring type: a type of the same name elsewhere, or a
  // sealed type of this file with no `permits` clause, is one.
  @Test
  void inFileType_anyTypePosition_suggested() {
    final Map<String, String> expected =
        Map.of(
            "class Test { static class Inner {} void m(Inn§ p) {} }",
            "Inner",
            "class Test { static class Inner {} Object m(Object o) { return (Inn§) o; } }",
            "Inner",
            "class Test { static class Inner {} record R(Inn§ a) {} }",
            "Inner",
            "class Test { void m() { java.util.function.Consumer<Inner> c = (Inn§ a) -> {}; }"
                + " static class Inner {} }",
            "Inner",
            "class Test { static class Boom extends Exception {} void m() throws Boo§ {} }",
            "Boom",
            "class Test implements Lis§ {} interface Listener {}",
            "Listener",
            "class Test extends Bas§ {} class Base {}",
            "Base",
            "interface Test extends Lis§ {} interface Listener {}",
            "Listener",
            "class Node extends Nod§ {} class Holder { static class Node {} }",
            "Node",
            "sealed class Shape {} final class Circle extends Shape {} class Test extends Sha§ {}",
            "Shape");
    expected.forEach(
        (source, label) -> assertThat(labels(fixture.complete(source))).as(source).contains(label));
  }

  // The last compile predates a nested type just typed, so the class around the cursor there, the
  // outer one, must not be taken for the declaring type and left out; nor is a type of the new
  // type's name elsewhere.
  @Test
  void inFileType_supertypeOfNewNestedType_suggestedFromStaleCompile() {
    assertThat(
            labels(
                fixture.completeWithCache(
                    "sealed interface Shape {%s}".formatted(" ".repeat(60)),
                    "sealed interface Shape { record Circle() implements Sha§ {} }")))
        .contains("Shape");
    assertThat(
            labels(
                fixture.completeWithCache(
                    "class Holder {%s} class Other { static class Node {} }"
                        .formatted(" ".repeat(60)),
                    "class Holder { static class Node extends Nod§ {} }"
                        + " class Other { static class Node {} }")))
        .contains("Node");
  }

  // The position's kind still filters them; a type is no supertype of itself, of a type nested
  // in it or of a subtype of it or a type nested in one, nor is a sealed type that does not permit
  // it, or any sealed type to a local class; a private type of another class is out of reach.
  @Test
  void inFileType_invalidAtPosition_notSuggested() {
    final Map<String, String> excluded =
        Map.ofEntries(
            Map.entry("class Test implements Lis§ {} class Listing {}", "Listing"),
            Map.entry("class Test extends Lis§ {} interface Listener {}", "Listener"),
            Map.entry("class Test extends Fin§ {} final class Finished {}", "Finished"),
            Map.entry("class Test { static class Plain {} void m() throws Pla§ {} }", "Plain"),
            Map.entry("class Tester extends Tes§ {}", "Tester"),
            Map.entry("interface Tester extends Tes§ {}", "Tester"),
            Map.entry("class Test implements Lis§ { interface Listener {} }", "Listener"),
            Map.entry(
                "sealed class Shape permits Circle {} final class Circle extends Shape {}"
                    + " class Test extends Sha§ {}",
                "Shape"),
            Map.entry(
                "class Holder { private static class Secret {} } class Test { void m(Sec§ p) {} }",
                "Secret"),
            Map.entry("class Base extends Sub {} class Sub extends Bas§ {}", "Base"),
            Map.entry(
                "class Base extends Nes§ {} class Sub extends Base { static class Nested {} }",
                "Nested"),
            Map.entry(
                "sealed class Shape {} final class Circle extends Shape {}"
                    + " class Test { void m() { class Local extends Sha§ {} } }",
                "Shape"));
    excluded.forEach(
        (source, label) ->
            assertThat(labels(fixture.complete(source))).as(source).doesNotContain(label));
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
