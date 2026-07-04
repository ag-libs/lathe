package io.github.aglibs.lathe.server.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.Test;

class CompletionCaseTest extends CompletionTestSupport {

  @Test
  void completion_caseInSealedSwitch_offersPermittedSubtypes() {
    final List<CompletionItem> items =
        fixture.complete(
            """
            sealed interface Shape permits Circle, Square {}
            record Circle() implements Shape {}
            record Square() implements Shape {}
            class Test {
              String describe(Shape s) {
                return switch (s) {
                  case §
                };
              }
            }""");

    assertThat(labels(items))
        .contains("Circle", "Square")
        .doesNotContain("String", "StringBuilder");
    assertThat(itemLabeled(items, "Circle"))
        .hasValueSatisfying(i -> assertThat(i.getInsertText()).isEqualTo("Circle circle ->"));
  }

  @Test
  void completion_caseInEnumSwitch_unchanged() {
    final List<CompletionItem> items =
        fixture.complete(
            """
            enum Color { RED, GREEN, BLUE }
            class Test {
              String describe(Color c) {
                return switch (c) {
                  case §
                };
              }
            }""");

    assertThat(labels(items)).contains("RED", "GREEN", "BLUE");
  }
}
