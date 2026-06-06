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
  void memberAccess_inTokenCompletion_replacesWholeIdentifier() {
    final String markedSource =
        """
        class Test {
            static class Builder {
                Builder setFieldPath(java.util.List<String> path) { return this; }
            }

            void m(Builder builder) {
                builder.set§FieldPath(null);
            }
        }""";
    final var cursor = CursorFixture.cursor(markedSource);
    final var item =
        fixture.complete(markedSource).stream()
            .filter(i -> "setFieldPath".equals(i.getFilterText()))
            .findFirst();

    assertThat(item).isPresent();
    assertThat(textInRange(cursor.content(), item.get().getTextEdit().getLeft().getRange()))
        .isEqualTo("setFieldPath");
  }

  @Test
  void completionItem_method_hasCorrectFilterTextAndSnippetInsertFormat() {
    final var item =
        fixture.complete("class Test { void m(java.util.ArrayList<String> l) { l.sub§ } }").stream()
            .filter(i -> "subList".equals(i.getFilterText()))
            .findFirst();
    assertThat(item).isPresent();
    assertThat(item.get().getLabel()).isEqualTo("subList");
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
  void completionItem_typeIndexType_hasPackageLabelDescription() {
    assertThat(itemLabeled(fixture.complete("class Test { ArrayD§ field; }"), "ArrayDeque"))
        .hasValueSatisfying(
            i -> {
              assertThat(i.getLabelDetails()).isNotNull();
              assertThat(i.getLabelDetails().getDescription()).isEqualTo("java.util");
              assertThat(i.getLabel()).isEqualTo("ArrayDeque");
              assertThat(i.getFilterText()).isEqualTo("ArrayDeque");
              assertThat(i.getInsertText()).isEqualTo("ArrayDeque");
              assertThat(i.getDetail()).isEqualTo("java.util.ArrayDeque");
            });
  }

  @Test
  void completionItem_javaLangType_hasPackageLabelDescription() {
    assertThat(itemLabeled(fixture.complete("class Test { Str§ field; }"), "String"))
        .hasValueSatisfying(
            i -> {
              assertThat(i.getLabelDetails()).isNotNull();
              assertThat(i.getLabelDetails().getDescription()).isEqualTo("java.lang");
            });
  }

  @Test
  void completionItem_genericReceiverMethod_usesSubstitutedReturnDetail() {
    assertThat(
            itemWithFilterText(
                fixture.complete("class Test { void m(java.util.List<String> list) { list.ge§ } }"),
                "get"))
        .hasValueSatisfying(
            i -> {
              assertThat(i.getLabel()).isEqualTo("get");
              assertThat(i.getLabelDetails()).isNotNull();
              assertThat(i.getLabelDetails().getDetail()).isEqualTo("(int)");
              assertThat(i.getLabelDetails().getDescription()).isEqualTo("String");
              assertThat(i.getDetail()).isEqualTo("List.get(int) : String");
            });
  }

  @Test
  void completionItem_method_usesLabelDetailsForSignatureAndReturn() {
    assertThat(
            itemWithFilterText(
                fixture.complete(
                    """
                    class Test {
                      static class Builder {
                        Builder setFieldPath(java.util.List<String> path) { return this; }
                      }
                      void m(Builder builder) { builder.set§ }
                    }"""),
                "setFieldPath"))
        .hasValueSatisfying(
            i -> {
              assertThat(i.getLabel()).isEqualTo("setFieldPath");
              assertThat(i.getLabelDetails()).isNotNull();
              assertThat(i.getLabelDetails().getDetail()).isEqualTo("(List<String> path)");
              assertThat(i.getLabelDetails().getDescription()).isEqualTo("Builder");
              assertThat(i.getDetail())
                  .isEqualTo("Builder.setFieldPath(List<String> path) : Builder");
            });
  }

  @Test
  void completionItem_genericField_usesFormattedTypeDetail() {
    assertThat(
            itemLabeled(
                fixture.complete(
                    """
                    class Test {
                      static class Box { java.util.List<String> names; }
                      void m(Box b) { b.na§ }
                    }"""),
                "names"))
        .hasValueSatisfying(
            i -> {
              assertThat(i.getDetail()).isEqualTo("List<String>");
              assertThat(i.getLabelDetails()).isNotNull();
              assertThat(i.getLabelDetails().getDescription()).isEqualTo("List<String>");
              assertThat(i.getInsertText()).isEqualTo("names");
            });
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

  private static String textInRange(final String content, final Range range) {
    final int start = CursorFixture.offset(content, range.getStart());
    final int end = CursorFixture.offset(content, range.getEnd());
    return content.substring(start, end);
  }
}
