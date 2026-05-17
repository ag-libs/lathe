package io.github.aglibs.lathe.server.analysis.completion;

import java.util.List;
import org.eclipse.lsp4j.CompletionItem;

public sealed interface CompletionResult {
  record Found(List<CompletionItem> items) implements CompletionResult {}

  record Declined() implements CompletionResult {}
}
