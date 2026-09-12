package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

// The expression-derived paths (getUser() -> user, obj.config -> config) are covered end to end by
// CodeActionTest's Extract Variable cases; this covers the pure type-derived derivation, keyword
// legalization, generic element names, and collision handling with a null expression.
class VariableNameSuggesterTest {

  private static List<String> names(final String type) {
    return VariableNameSuggester.suggest(type, null, null, Set.of());
  }

  private static List<String> names(final String type, final String element) {
    return VariableNameSuggester.suggest(type, element, null, Set.of());
  }

  @Test
  void suggest_multiWordType_offersFullNameThenLastWord() {
    assertThat(names("ConnectionString")).containsExactly("connectionString", "string");
  }

  @Test
  void suggest_singleWordType_offersOneName() {
    assertThat(names("String")).containsExactly("string");
  }

  @Test
  void suggest_keywordName_fallsBackToValue() {
    // decapitalize("Class") is the keyword `class`, which cannot be an identifier.
    assertThat(names("Class")).containsExactly("value");
  }

  @Test
  void suggest_collectionType_leadsWithThePluralElement() {
    assertThat(names("List", "User")).containsExactly("users", "userList", "list");
    assertThat(names("Set", "Entry")).containsExactly("entries", "entrySet", "set");
  }

  @Test
  void suggest_mapType_usesTheValueElement() {
    assertThat(names("Map", "User")).containsExactly("users", "userMap", "map");
  }

  @Test
  void suggest_nonCollectionGeneric_offersContainerThenSingularElement() {
    assertThat(names("Optional", "User")).containsExactly("optional", "user");
  }

  @Test
  void suggest_multiWordTypeWithFirstTaken_uniquifiesEachIndependently() {
    assertThat(
            VariableNameSuggester.suggest(
                "ConnectionString", null, null, Set.of("connectionString")))
        .containsExactly("connectionString1", "string");
  }

  @Test
  void suggest_noResolvableType_fallsBackToDefault() {
    assertThat(names(null)).containsExactly("value");
    assertThat(names("")).containsExactly("value");
  }
}
