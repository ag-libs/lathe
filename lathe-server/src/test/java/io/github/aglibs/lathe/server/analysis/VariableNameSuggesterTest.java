package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

// The expression-derived paths (getUser() -> user, obj.config -> config) are covered end to end by
// CodeActionTest's Extract Variable cases; this covers the pure type-derived derivation, keyword
// legalization, and collision handling with a null expression.
class VariableNameSuggesterTest {

  @Test
  void suggest_multiWordType_offersFullNameThenLastWord() {
    assertThat(VariableNameSuggester.suggest("ConnectionString", null, Set.of()))
        .containsExactly("connectionString", "string");
  }

  @Test
  void suggest_singleWordType_offersOneName() {
    assertThat(VariableNameSuggester.suggest("String", null, Set.of())).containsExactly("string");
  }

  @Test
  void suggest_keywordName_fallsBackToValue() {
    // decapitalize("Class") is the keyword `class`, which cannot be an identifier.
    assertThat(VariableNameSuggester.suggest("Class", null, Set.of())).containsExactly("value");
  }

  @Test
  void suggest_multiWordTypeWithFirstTaken_uniquifiesEachIndependently() {
    assertThat(VariableNameSuggester.suggest("ConnectionString", null, Set.of("connectionString")))
        .containsExactly("connectionString1", "string");
  }

  @Test
  void suggest_noResolvableType_fallsBackToDefault() {
    assertThat(VariableNameSuggester.suggest(null, null, Set.of())).containsExactly("value");
    assertThat(VariableNameSuggester.suggest("", null, Set.of())).containsExactly("value");
  }
}
