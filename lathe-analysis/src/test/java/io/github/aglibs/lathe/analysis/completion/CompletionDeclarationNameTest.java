package io.github.aglibs.lathe.analysis.completion;

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

  // A record body drops a member javac reads as an instance field, yet the name slot answers as in
  // a class body.
  @Test
  void declarationName_recordMemberSlot_offersNamesAsInAClassBody() {
    assertThat(labels(fixture.complete("record Test(int x) { ConnectionString § }")))
        .containsExactly("connectionString", "string");
    assertThat(labels(fixture.complete("record Test(int x) { private List<User> u§ }")))
        .containsExactly("users", "userList");
  }

  @Test
  void declarationName_genericAndQualifiedTypes_useTheSimpleName() {
    assertThat(labels(fixture.complete("class Test { void m() { java.util.List §; } }")))
        .containsExactly("list");
  }

  @Test
  void declarationName_genericCollection_leadsWithThePluralElement() {
    assertThat(labels(fixture.complete("class Test { void m() { List<User> §; } }")))
        .containsExactly("users", "userList", "list");
    assertThat(labels(fixture.complete("class Test { void m() { Map<String, User> §; } }")))
        .containsExactly("users", "userMap", "map");
  }

  @Test
  void declarationName_typedPrefix_filtersToMatchingNames() {
    assertThat(labels(fixture.complete("class Test { void m() { ConnectionString c§; } }")))
        .containsExactly("connectionString");
  }

  @Test
  void declarationName_catchParameter_leadsWithExceptionIdioms() {
    assertThat(labels(fixture.complete("class T { void m() { try {} catch (IOException §) {} } }")))
        .containsExactly("e", "ex", "exception", "ioException");
  }

  @Test
  void declarationName_catchParameterWithIdiomInScope_skipsTakenIdiom() {
    assertThat(
            labels(
                fixture.complete("class T { void m(int e) { try {} catch (IOException §) {} } }")))
        .containsExactly("ex", "exception", "ioException");
  }

  @Test
  void declarationName_nameAlreadyInScope_offersUniqueVariant() {
    assertThat(labels(fixture.complete("class Test { void m(String string) { String §; } }")))
        .containsExactly("string1");
  }

  @Test
  void declarationName_staticFinalFieldSlot_offersConstantCaseNamesAsFields() {
    final var items = fixture.complete("class Test { static final Logger §; }");
    assertThat(labels(items)).containsExactly("LOGGER");
    assertThat(itemLabeled(items, "LOGGER").orElseThrow().getKind())
        .isEqualTo(CompletionItemKind.Field);
  }

  @Test
  void declarationName_staticFinalMultiWordType_screamingSnakesEachName() {
    assertThat(labels(fixture.complete("class Test { static final ConnectionString §; }")))
        .containsExactly("CONNECTION_STRING", "STRING");
  }

  @Test
  void declarationName_plainStaticField_keepsCamelCase() {
    assertThat(labels(fixture.complete("class Test { static ConnectionString §; }")))
        .containsExactly("connectionString", "string");
  }
}
