package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.server.analysis.FileAnalysis;
import io.github.aglibs.lathe.server.analysis.SourceCompiler;
import io.github.aglibs.lathe.server.analysis.SourceParser;
import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;

public final class CompletionEngine {

  public CompletionEngine(final SourceCompiler compiler, final SourceParser parser) {}

  public List<CompletionItem> complete(
      final String uri,
      final String content,
      final Position pos,
      final String cachedContent,
      final FileAnalysis cachedAnalysis) {

    // TODO: implement

    return List.of();
  }
}
