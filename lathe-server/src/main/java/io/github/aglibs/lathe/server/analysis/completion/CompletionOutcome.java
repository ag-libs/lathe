package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.server.analysis.FileAnalysis;
import java.util.List;
import org.eclipse.lsp4j.CompletionItem;

public record CompletionOutcome(List<CompletionItem> items, FileAnalysis freshAnalysis) {

  static CompletionOutcome of(final List<CompletionItem> items) {
    return new CompletionOutcome(items, null);
  }
}
