package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.server.analysis.SourceParser;
import java.util.List;
import java.util.logging.Logger;
import org.eclipse.lsp4j.CompletionItem;

public final class CompletionEngine {

  private static final Logger LOG = Logger.getLogger(CompletionEngine.class.getName());

  private final SentinelParser sentinelParser;

  public CompletionEngine(final SourceParser parser) {
    this.sentinelParser = new SentinelParser(parser);
  }

  public List<CompletionItem> complete(final CompletionRequest req) {
    final var injected = new SentinelInjector(req.content()).inject(req.cursorOffset());
    LOG.fine(
        () ->
            "[completion] inject prefix=|%s| receiver=|%s| ctx=%s"
                .formatted(injected.prefix(), injected.receiverText(), injected.context()));

    final int version = req.cached() != null ? req.cached().version() : -1;
    final var parsed = sentinelParser.parse(injected, req.pos().getLine(), version);
    LOG.fine(
        () ->
            "[completion] parsed valid=%s sentinelCtx=%s"
                .formatted(parsed.valid(), parsed.sentinelContext()));

    if (parsed.valid()
        && parsed.sentinelContext() == SentinelContext.MEMBER_ACCESS
        && req.cached() != null) {
      final var snapshot = req.cached().analysis();
      final var receiverType =
          TypeResolver.resolveReceiverType(parsed, req.pos().getLine(), snapshot);
      LOG.fine(
          () ->
              "[completion] resolve receiver=|%s| type=%s"
                  .formatted(parsed.receiverText(), receiverType));
    }

    return List.of();
  }
}
