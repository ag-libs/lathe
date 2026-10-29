package io.github.aglibs.lathe.core.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

final class TestFilterDataTest {

  // As a front-end writes Surefire's defaults: **/*Test.java included, nested classes (**/*$*) out.
  @Test
  void includes_classNames_matchAnIncludeAndNoExclude_emptyIncludesTakeEverything() {
    final var filter =
        new TestFilterData(List.of("(.*\\.)?[^.]*Test"), List.of("(.*\\.)?[^.]*\\$[^.]*"));

    assertThat(filter.includes("com.example.FooTest")).isTrue();
    assertThat(filter.includes("com.example.SignedJarIT")).as("no include matches").isFalse();
    assertThat(filter.includes("com.example.FooTest$Inner")).as("excluded").isFalse();
    assertThat(new TestFilterData(null, null).includes("com.example.SignedJarIT")).isTrue();
  }
}
