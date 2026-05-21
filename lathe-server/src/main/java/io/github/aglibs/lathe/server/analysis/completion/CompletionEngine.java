package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.server.analysis.SourceParser;
import java.util.List;
import java.util.logging.Logger;
import org.eclipse.lsp4j.CompletionItem;

public final class CompletionEngine {

  private static final Logger LOG = Logger.getLogger(CompletionEngine.class.getName());

  private final SourceParser parser;

  public CompletionEngine(final SourceParser parser) {
    this.parser = parser;
  }

  public List<CompletionItem> complete(final CompletionRequest req) {
    return List.of();
  }
}
