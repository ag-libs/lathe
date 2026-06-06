package io.github.aglibs.lathe.server.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
