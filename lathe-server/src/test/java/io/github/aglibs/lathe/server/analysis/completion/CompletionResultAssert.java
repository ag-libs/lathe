package io.github.aglibs.lathe.server.analysis.completion;

import java.util.List;
import org.assertj.core.api.AbstractAssert;
import org.eclipse.lsp4j.CompletionItem;

final class CompletionResultAssert
    extends AbstractAssert<CompletionResultAssert, List<CompletionItem>> {

  private CompletionResultAssert(final List<CompletionItem> items) {
    super(items, CompletionResultAssert.class);
  }

  static CompletionResultAssert assertThatCompletion(final List<CompletionItem> items) {
    return new CompletionResultAssert(items);
  }

  CompletionResultAssert containsLabel(final String label) {
    isNotNull();
    if (actual.stream().noneMatch(i -> label.equals(i.getLabel()))) {
      failWithMessage(
          "Expected completion to contain label <%s> but labels were <%s>", label, labels());
    }
    return this;
  }

  CompletionResultAssert doesNotContainLabel(final String label) {
    isNotNull();
    if (actual.stream().anyMatch(i -> label.equals(i.getLabel()))) {
      failWithMessage("Expected completion not to contain label <%s> but it was present", label);
    }
    return this;
  }

  CompletionItemAssert item(final String label) {
    isNotNull();
    return actual.stream()
        .filter(i -> label.equals(i.getLabel()))
        .findFirst()
        .map(CompletionItemAssert::new)
        .orElseThrow(
            () -> failure("No completion item with label <%s>. Labels: <%s>", label, labels()));
  }

  private List<String> labels() {
    return actual.stream().map(CompletionItem::getLabel).toList();
  }
}
