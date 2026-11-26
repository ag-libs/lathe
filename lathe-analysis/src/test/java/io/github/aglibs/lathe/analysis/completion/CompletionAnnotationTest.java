package io.github.aglibs.lathe.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Comparator;
import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Test;

class CompletionAnnotationTest extends CompletionTestSupport {

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

  // CQ-0081: a bare argument is the `value()` shorthand or the start of a named element, so an
  // annotation that declares `value()` offers both; after `=` only the element's value is.
  private static final String REQUIRES =
      """
      package p;
      @interface Requires {
        String value() default "";
        String property() default "";
        Class<?>[] beans() default {};
      }
      """;

  @Test
  void annotationArgument_annotationWithValueElement_offersElementNamesAndValue() {
    assertThat(labels(fixture.complete(REQUIRES + "@Requires(§) class Test {}")))
        .contains("value", "property", "beans", "null");
    assertThat(labels(fixture.complete(REQUIRES + "@Requires(pro§) class Test {}")))
        .contains("property")
        .doesNotContain("beans", "value");
    assertThat(labels(fixture.complete(REQUIRES + "class Test { @Requires(§) void m() {} }")))
        .contains("property", "beans");
  }

  // Beside other arguments a bare name can only start a named element: the shorthand is the sole
  // argument's.
  @Test
  void annotationArgument_besideOtherArguments_offersElementNamesOnly() {
    assertThat(labels(fixture.complete(REQUIRES + "@Requires(property = \"a\", §) class Test {}")))
        .contains("beans", "value")
        .doesNotContain("null");
  }

  @Test
  void annotationArgumentValue_afterEqualsOrInAnArray_offersNoElementNames() {
    assertThat(labels(fixture.complete(REQUIRES + "@Requires(value = §) class Test {}")))
        .containsOnly("null");
    assertThat(labels(fixture.complete(REQUIRES + "@Requires(property = §) class Test {}")))
        .containsOnly("null");
    assertThat(
            labels(
                fixture.complete(
                    "import java.lang.annotation.*; @Target({ § }) @interface Marker {}")))
        .doesNotContain("value");
  }

  @Test
  void annotationArgument_onlyValueElement_offersItsNameAndItsValues() {
    final List<String> items =
        labels(
            fixture.complete(
                """
                import java.lang.annotation.Retention;

                @Retention(§)
                class Test {}
                """));

    assertThat(items).contains("value", "RetentionPolicy.RUNTIME");
    assertThat(
            bySortText(
                fixture.complete(
                    "import java.lang.annotation.Retention; @Retention(§) class Test {}")))
        .as("the names, then the values")
        .containsSubsequence("value", "RetentionPolicy.RUNTIME");
  }

  private static List<String> bySortText(final List<CompletionItem> items) {
    return items.stream()
        .sorted(Comparator.comparing(CompletionItem::getSortText))
        .map(CompletionItem::getLabel)
        .toList();
  }

  @Test
  void annotationValue_memberAccessOnNonEnumType_offersConstants() {
    // Member access on a non-enum type inside a String-valued annotation element must not
    // crash the ranker; it should offer the receiver type's constants like member access anywhere.
    assertThat(
            labels(
                fixture.complete(
                    """
                    package p;
                    class Test {
                      @SuppressWarnings(java.io.File.§separator)
                      void m() {}
                    }""")))
        .contains("separator");
  }

  @Test
  void annotationValue_classTypedElement_offersTypeCandidates() {
    // A Class-typed annotation value element (like @ExtendWith's Class<? extends Extension>
    // value) should offer type candidates in value position. Currently only keywords are offered.
    assertThat(
            labels(
                fixture.complete(
                    """
                    package p;
                    @interface Uses { Class<?> value(); }
                    class Ext {}
                    @Uses(E§)
                    class Test {}""")))
        .contains("Ext");
  }

  @Test
  void annotationArgument_elementItem_hasPropertyPresentation() {
    assertThat(itemLabeled(fixture.complete("@Deprecated(si§) class Test {}"), "since"))
        .hasValueSatisfying(
            i -> {
              assertThat(i.getKind()).isEqualTo(CompletionItemKind.Property);
              assertThat(i.getFilterText()).isEqualTo("since");
              assertThat(i.getDetail()).isEqualTo("java.lang.String");
              assertThat(i.getInsertText()).isEqualTo("since = ");
            });
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
  void annotationArgumentValue_enumMemberAccess_offersEnumConstants() {
    final List<String> items =
        labels(
            fixture.complete(
                """
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;

                @Retention(RetentionPolicy.§)
                class Test {}
                """));

    assertThat(items).contains("RUNTIME");
  }

  @Test
  void annotationArgumentValue_enumElement_offersQualifiedConstantsAndImportsTheEnum() {
    final CompletionItem item =
        itemLabeled(
                fixture.complete(
                    """
                    import java.lang.annotation.Retention;

                    @Retention(§)
                    class Test {}
                    """),
                "RetentionPolicy.RUNTIME")
            .orElseThrow();

    assertThat(item.getInsertText()).isEqualTo("RetentionPolicy.RUNTIME");
    assertThat(item.getAdditionalTextEdits())
        .extracting(TextEdit::getNewText)
        .anySatisfy(
            text -> assertThat(text).contains("import java.lang.annotation.RetentionPolicy;"));
  }

  @Test
  void annotationArgumentValue_enumArrayElement_offersComponentEnumConstants() {
    final List<String> items =
        labels(
            fixture.complete(
                """
                import java.lang.annotation.Target;

                @Target({ § })
                class Test {}
                """));

    assertThat(items).contains("ElementType.FIELD", "ElementType.PARAMETER");
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
}
