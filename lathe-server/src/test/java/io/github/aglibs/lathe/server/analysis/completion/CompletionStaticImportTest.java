package io.github.aglibs.lathe.server.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.analysis.LearnedStaticImports;
import java.util.List;
import java.util.Map;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Test;

class CompletionStaticImportTest extends CompletionTestSupport {

  private static final LearnedStaticImports LEARNED =
      new LearnedStaticImports(
          Map.of(
              "java.util.Objects.requireNonNull", 3,
              "java.util.Collections.*", 1,
              "com.example.Missing.helper", 5));

  // A member the scope imports statically is offered with its `import static`, one item per name
  // (an overloaded method shows `(…)`), after what is in scope and ordered by how many files import
  // it; a learned class with `.*` offers its members; an owner off the classpath offers nothing.
  @Test
  void completion_learnedStaticImports_offeredWithTheirImport() {
    final var learning = fixture.learning(LEARNED);

    final List<CompletionItem> required =
        learning.complete("class Test { void m(Object o) { requ§ } }");
    assertThat(itemLabeled(required, "requireNonNull"))
        .hasValueSatisfying(
            item -> {
              assertThat(item.getDetail()).isEqualTo("java.util.Objects");
              assertThat(item.getLabelDetails().getDetail()).isEqualTo("(…)");
              assertThat(item.getAdditionalTextEdits())
                  .extracting(TextEdit::getNewText)
                  .containsExactly("import static java.util.Objects.requireNonNull;\n");
            });
    assertThat(labels(learning.complete("class Test { void m() { empty§ } }")))
        .contains("emptyList", "emptyMap");
    assertThat(labels(learning.complete("class Test { void m() { help§ } }")))
        .doesNotContain("helper");
    assertThat(labels(learning.complete("class Test { void requestIt() {} void m() { requ§ } }")))
        .containsSubsequence("requestIt", "requireNonNull");
  }

  // Nothing learned (a scope without static imports, such as main code that avoids them) offers
  // nothing; a member the file already imports statically is offered as in scope, not again.
  @Test
  void completion_notLearnedOrAlreadyImported_noStaticImportItem() {
    assertThat(labels(fixture.complete("class Test { void m(Object o) { requ§ } }")))
        .doesNotContain("requireNonNull");
    assertThat(
            fixture
                .learning(LEARNED)
                .complete(
                    """
                    import static java.util.Objects.requireNonNull;
                    class Test { void m(Object o) { requ§ } }"""))
        .filteredOn(item -> item.getLabel().equals("requireNonNull"))
        .allMatch(item -> item.getAdditionalTextEdits() == null);
  }
}
