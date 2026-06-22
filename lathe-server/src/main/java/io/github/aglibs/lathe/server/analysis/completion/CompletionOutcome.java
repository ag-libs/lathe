package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import java.util.List;
import org.eclipse.lsp4j.CompletionItem;

public record CompletionOutcome(
    List<CompletionItem> items, AttributedFileAnalysis freshAnalysis, boolean incomplete) {

  public CompletionOutcome {
    items = items != null ? List.copyOf(items) : null;
  }

  public CompletionOutcome(
      final List<CompletionItem> items, final AttributedFileAnalysis freshAnalysis) {
    this(items, freshAnalysis, false);
  }

  public static CompletionOutcome of(final List<CompletionItem> items) {
    return new CompletionOutcome(items, null, false);
  }

  static CompletionOutcome incomplete(final List<CompletionItem> items) {
    return new CompletionOutcome(items, null, true);
  }
}
