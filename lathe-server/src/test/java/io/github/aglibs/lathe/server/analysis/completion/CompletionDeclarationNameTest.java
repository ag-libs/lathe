package io.github.aglibs.lathe.server.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.lsp4j.CompletionItemKind;
import org.junit.jupiter.api.Test;

// A real name slot (an explicit type before the caret) now offers type-derived identifier names,
// where it used to return nothing. Derivation itself is unit-tested in VariableNameSuggesterTest;
// these assert the completion wiring: kind, prefix filter, scope collision, field-vs-local split.
class CompletionDeclarationNameTest extends CompletionTestSupport {

  @Test
  void declarationName_localSlot_offersTypeDerivedNamesAsVariables() {
    final var items = fixture.complete("class Test { void m() { ConnectionString §; } }");
    assertThat(labels(items)).containsExactly("connectionString", "string");
    assertThat(itemLabeled(items, "connectionString").orElseThrow().getKind())
        .isEqualTo(CompletionItemKind.Variable);
  }

  @Test
  void declarationName_fieldSlot_offersNamesAsFields() {
    final var items = fixture.complete("class Test { ConnectionString §; }");
    assertThat(labels(items)).contains("connectionString");
    assertThat(itemLabeled(items, "connectionString").orElseThrow().getKind())
        .isEqualTo(CompletionItemKind.Field);
  }

  @Test
  void declarationName_genericAndQualifiedTypes_useTheSimpleName() {
    assertThat(labels(fixture.complete("class Test { void m() { List<String> §; } }")))
        .containsExactly("list");
    assertThat(labels(fixture.complete("class Test { void m() { java.util.List §; } }")))
        .containsExactly("list");
  }

  @Test
  void declarationName_typedPrefix_filtersToMatchingNames() {
    assertThat(labels(fixture.complete("class Test { void m() { ConnectionString c§; } }")))
        .containsExactly("connectionString");
  }

  @Test
  void declarationName_nameAlreadyInScope_offersUniqueVariant() {
    assertThat(labels(fixture.complete("class Test { void m(String string) { String §; } }")))
        .containsExactly("string1");
  }
}
