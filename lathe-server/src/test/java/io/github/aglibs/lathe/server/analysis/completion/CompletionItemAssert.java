package io.github.aglibs.lathe.server.analysis.completion;

import org.assertj.core.api.AbstractAssert;
import org.eclipse.lsp4j.CompletionItem;

final class CompletionItemAssert extends AbstractAssert<CompletionItemAssert, CompletionItem> {

  CompletionItemAssert(final CompletionItem item) {
    super(item, CompletionItemAssert.class);
  }

  CompletionItemAssert hasImportEdit(final String qualifiedName) {
    return hasImportTextEdit("import %s;".formatted(qualifiedName));
  }

  CompletionItemAssert hasStaticImportEdit(final String qualifiedName) {
    return hasImportTextEdit("import static %s;".formatted(qualifiedName));
  }

  // The line breaks around an import, and the blank line a file's first import gets below its
  // package, are ImportAnalyzerTest's concern.
  private CompletionItemAssert hasImportTextEdit(final String importText) {
    isNotNull();
    final var edits = actual.getAdditionalTextEdits();
    if (edits == null || edits.stream().noneMatch(e -> importText.equals(e.getNewText().strip()))) {
      failWithMessage(
          "Expected item <%s> to have additional text edit <%s> but additional edits were <%s>",
          actual.getLabel(), importText, edits);
    }
    return this;
  }
}
