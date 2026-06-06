package io.github.aglibs.lathe.server.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.typeindex.TypeKind;
import java.io.IOException;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

class CompletionPresentationTest extends CompletionTestSupport {

  @Test
  void memberAccess_item_hasTextEdit() {
    // gap #2: Every item must carry a textEdit so prefix replacement is correct
    final var items =
        fixture.complete("class Test { void m(java.util.ArrayList<String> l) { l.toS§ } }");
    assertThat(items).isNotEmpty();
    assertThat(items).allMatch(i -> i.getTextEdit() != null);
  }

  @Test
  void completionItem_method_hasCorrectFilterTextAndSnippetInsertFormat() {
    final var item =
        fixture.complete("class Test { void m(java.util.ArrayList<String> l) { l.sub§ } }").stream()
            .filter(i -> i.getLabel().startsWith("subList("))
            .findFirst();
    assertThat(item).isPresent();
    assertThat(item.get().getFilterText()).isEqualTo("subList");
    assertThat(item.get().getInsertTextFormat()).isEqualTo(InsertTextFormat.Snippet);
    assertThat(item.get().getInsertText()).contains("$");
  }

  @Test
  void typeIndex_itemKind_interfaceAndEnumMappedCorrectly() {
    assertThat(itemLabeled(fixture.complete("class Test implements Runn§ {}"), "Runnable"))
        .hasValueSatisfying(i -> assertThat(i.getKind()).isEqualTo(CompletionItemKind.Interface));
    assertThat(itemLabeled(fixture.complete("class Test { TimeU§ field; }"), "TimeUnit"))
        .hasValueSatisfying(i -> assertThat(i.getKind()).isEqualTo(CompletionItemKind.Enum));
  }

  @Test
  void completionItem_importEdit_insertedAfterLastExistingImport() throws IOException {
    localFixture =
        new CompletionFixture(
            CompletionFixture.typeIndex(
                tmp.resolve("index.json"),
                CompletionFixture.typeEntry("ArrayList", "java.util.ArrayList", TypeKind.CLASS)));
    final var item =
        itemLabeled(
            localFixture.complete(
                """
                package example;

                import java.util.List;

                class Test {
                  void accept(Object v) {}
                  void m() { accept(new ArrayL§); }
                }"""),
            "ArrayList");
    assertThat(item).isPresent();
    final var edit = item.get().getAdditionalTextEdits().getFirst();
    assertThat(edit.getNewText()).isEqualTo("import java.util.ArrayList;\n");
    assertThat(edit.getRange()).isEqualTo(new Range(new Position(3, 0), new Position(3, 0)));
  }

  // ── type index ────────────────────────────────────────────────────────────────
}
