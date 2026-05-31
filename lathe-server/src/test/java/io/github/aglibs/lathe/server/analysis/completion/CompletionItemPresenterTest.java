package io.github.aglibs.lathe.server.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Test;

class CompletionItemPresenterTest {

  @Test
  void applyReplacementRange_textEditReplacesPrefix() {
    final CompletionItem item = CompletionItemPresenter.present(candidate("stringValue"));
    final Range range = new Range(new Position(2, 12), new Position(2, 15));

    CompletionItemPresenter.applyReplacementRange(List.of(item), range);

    final TextEdit edit = item.getTextEdit().getLeft();
    assertThat(edit.getRange()).isEqualTo(range);
    assertThat(edit.getNewText()).isEqualTo("stringValue");
  }

  @Test
  void present_filterTextEqualsName() {
    final CompletionItem item =
        CompletionItemPresenter.present(methodCandidate("format", "format(String)", "format($1)"));

    assertThat(item.getFilterText()).isEqualTo("format");
  }

  @Test
  void present_methodWithParams_usesSnippetFormat() {
    final CompletionItem item =
        CompletionItemPresenter.present(methodCandidate("format", "format(String)", "format($1)"));

    assertThat(item.getInsertText()).isEqualTo("format($1)");
    assertThat(item.getInsertTextFormat()).isEqualTo(InsertTextFormat.Snippet);
  }

  @Test
  void present_typeKinds_mapToLspKinds() {
    assertKind(typeCandidate("Service", CandidateKind.TYPE_CLASS), CompletionItemKind.Class);
    assertKind(
        typeCandidate("ServiceApi", CandidateKind.TYPE_INTERFACE), CompletionItemKind.Interface);
    assertKind(typeCandidate("Status", CandidateKind.TYPE_ENUM), CompletionItemKind.Enum);
  }

  @Test
  void applyImportEdits_importEdit_addsImportTextEdit() {
    final CompletionCandidate candidate =
        candidateWithImport("ArrayList", CandidateKind.TYPE_CLASS, "java.util.ArrayList", false);
    final CompletionItem item = CompletionItemPresenter.present(candidate);

    CompletionItemPresenter.applyImportEdits(
        List.of(candidate),
        List.of(item),
        analysis(
            """
            package example;

            class Test {}
            """));

    new CompletionItemAssert(item).hasImportEdit("java.util.ArrayList");
  }

  @Test
  void applyImportEdits_staticImportEdit_addsStaticImportTextEdit() {
    final CompletionCandidate candidate =
        candidateWithImport(
            "empty", CandidateKind.LOCAL_VARIABLE, "java.util.Collections.emptyList", true);
    final CompletionItem item = CompletionItemPresenter.present(candidate);

    CompletionItemPresenter.applyImportEdits(
        List.of(candidate),
        List.of(item),
        analysis(
            """
            package example;

            class Test {}
            """));

    new CompletionItemAssert(item).hasStaticImportEdit("java.util.Collections.emptyList");
  }

  @Test
  void importInsertionRange_afterLastImport() {
    final Range range =
        CompletionItemPresenter.importInsertionRange(
            analysis(
                """
                package example;

                import java.util.List;
                import java.util.Map;

                class Test {}
                """));

    assertThat(range).isEqualTo(new Range(new Position(4, 0), new Position(4, 0)));
  }

  @Test
  void importInsertionRange_afterPackageWhenNoImports() {
    final Range range =
        CompletionItemPresenter.importInsertionRange(
            analysis(
                """
                package example;

                class Test {}
                """));

    assertThat(range).isEqualTo(new Range(new Position(1, 0), new Position(1, 0)));
  }

  @Test
  void importInsertionRange_atTopWhenNoPackageOrImports() {
    final Range range =
        CompletionItemPresenter.importInsertionRange(
            analysis(
                """
                class Test {}
                """));

    assertThat(range).isEqualTo(new Range(new Position(0, 0), new Position(0, 0)));
  }

  private static void assertKind(
      final CompletionCandidate candidate, final CompletionItemKind kind) {
    new CompletionItemAssert(CompletionItemPresenter.present(candidate)).hasKind(kind);
  }

  private static CompletionCandidate typeCandidate(final String name, final CandidateKind kind) {
    return new CompletionCandidate(
        name, name, kind, "example." + name, name, false, null, null, "example." + name, null);
  }

  private static CompletionCandidate methodCandidate(
      final String name, final String label, final String insertText) {
    return new CompletionCandidate(
        name, label, CandidateKind.METHOD, "String", insertText, true, null, null, "Test", null);
  }

  private static CompletionCandidate candidate(final String name) {
    return new CompletionCandidate(
        name, name, CandidateKind.LOCAL_VARIABLE, null, name, false, null, null, null, null);
  }

  private static CompletionCandidate candidateWithImport(
      final String name,
      final CandidateKind kind,
      final String qualifiedName,
      final boolean isStatic) {
    return new CompletionCandidate(
        name,
        name,
        kind,
        qualifiedName,
        name,
        false,
        null,
        null,
        qualifiedName,
        new ImportEdit(qualifiedName, isStatic));
  }

  private static AttributedFileAnalysis analysis(final String source) {
    try (CompletionFixture fixture = new CompletionFixture()) {
      return fixture.analysis(source);
    }
  }
}
