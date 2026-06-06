package io.github.aglibs.lathe.server.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.eclipse.lsp4j.CompletionItemKind;
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
  void annotationArgumentValue_enumElement_offersEnumConstants() {
    final List<String> items =
        labels(
            fixture.complete(
                """
                import java.lang.annotation.Retention;

                @Retention(§)
                class Test {}
                """));

    assertThat(items).contains("RUNTIME");
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

    assertThat(items).contains("FIELD", "PARAMETER");
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
