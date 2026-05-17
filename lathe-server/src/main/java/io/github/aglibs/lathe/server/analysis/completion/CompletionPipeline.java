package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.server.analysis.FileAnalysis;
import io.github.aglibs.lathe.server.analysis.SourceCompiler;
import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;

public final class CompletionPipeline {

  private final List<CompletionStrategy> strategies;

  public CompletionPipeline(final SourceCompiler compiler) {
    this.strategies =
        List.of(
            new StaleCacheStrategy(),
            new SentinelStrategy(compiler),
            new RepairStrategy(compiler, new DiagnosticRepairer()));
  }

  public List<CompletionItem> complete(
      final String uri,
      final String source,
      final Position pos,
      final String cachedSource,
      final FileAnalysis cached) {
    final var request = CompletionRequest.of(uri, source, pos, cachedSource, cached);
    for (final var strategy : strategies) {
      final var result = strategy.attempt(request);
      if (result instanceof CompletionResult.Found found) {
        return found.items();
      }
    }

    return List.of();
  }
}
