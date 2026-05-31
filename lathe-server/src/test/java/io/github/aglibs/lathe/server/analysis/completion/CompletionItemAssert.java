package io.github.aglibs.lathe.server.analysis.completion;

import org.assertj.core.api.AbstractAssert;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;

final class CompletionItemAssert extends AbstractAssert<CompletionItemAssert, CompletionItem> {

  CompletionItemAssert(final CompletionItem item) {
    super(item, CompletionItemAssert.class);
  }

  CompletionItemAssert hasKind(final CompletionItemKind kind) {
    isNotNull();
    if (actual.getKind() != kind) {
      failWithMessage(
          "Expected item <%s> to have kind <%s> but was <%s>",
          actual.getLabel(), kind, actual.getKind());
    }
    return this;
  }

  CompletionItemAssert hasFilterText(final String filterText) {
    isNotNull();
    if (!filterText.equals(actual.getFilterText())) {
      failWithMessage(
          "Expected item <%s> to have filterText <%s> but was <%s>",
          actual.getLabel(), filterText, actual.getFilterText());
    }
    return this;
  }

  CompletionItemAssert hasImportEdit(final String qualifiedName) {
    return hasImportTextEdit("import %s;\n".formatted(qualifiedName));
  }

  CompletionItemAssert hasStaticImportEdit(final String qualifiedName) {
    return hasImportTextEdit("import static %s;\n".formatted(qualifiedName));
  }

  private CompletionItemAssert hasImportTextEdit(final String importText) {
    isNotNull();
    final var edits = actual.getAdditionalTextEdits();
    if (edits == null || edits.stream().noneMatch(e -> importText.equals(e.getNewText()))) {
      failWithMessage(
          "Expected item <%s> to have additional text edit <%s> but additional edits were <%s>",
          actual.getLabel(), importText, edits);
    }
    return this;
  }
}
