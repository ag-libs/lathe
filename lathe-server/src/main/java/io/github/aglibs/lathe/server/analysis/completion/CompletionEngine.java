package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.server.analysis.SourceCompiler;
import io.github.aglibs.lathe.server.analysis.SourceParser;
import java.util.List;
import java.util.logging.Logger;
import org.eclipse.lsp4j.CompletionItem;

public final class CompletionEngine {

  private static final Logger LOG = Logger.getLogger(CompletionEngine.class.getName());

  private final SentinelParser sentinelParser;
  private final SourceCompiler compiler;

  public CompletionEngine(final SourceParser parser, final SourceCompiler compiler) {
    this.sentinelParser = new SentinelParser(parser);
    this.compiler = compiler;
  }

  public CompletionOutcome complete(final CompletionRequest req) {
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

    final var ctx = parsed.sentinelContext();
    if (parsed.valid()
        && (ctx == SentinelContext.MEMBER_ACCESS || ctx == SentinelContext.LAMBDA_BODY)
        && req.cached() != null) {

      final int dotOffset = injected.receiverText() != null ? injected.tokenStart() - 1 : -1;
      final var initialSnapshot = req.cached().analysis();
      final var initialType =
          TypeResolver.resolveReceiverType(parsed, req.pos().getLine(), dotOffset, initialSnapshot);

      final var freshAnalysis =
          (initialType == null && compiler != null && !req.noDiff())
              ? compiler.reattribute(req.uri(), req.content())
              : null;

      final var snapshot = freshAnalysis != null ? freshAnalysis : initialSnapshot;
      final var receiverType =
          freshAnalysis != null
              ? TypeResolver.resolveReceiverType(
                  parsed, req.pos().getLine(), dotOffset, freshAnalysis)
              : initialType;

      LOG.fine(
          () ->
              "[completion] resolve receiver=|%s| type=%s reattributed=%s"
                  .formatted(parsed.receiverText(), receiverType, freshAnalysis != null));

      if (receiverType != null) {
        final var text = parsed.receiverText();
        final boolean isStaticAccess =
            text != null
                && text.indexOf('(') < 0
                && (text.indexOf('.') < 0
                    ? Character.isUpperCase(text.charAt(0))
                    : snapshot.elements().getTypeElement(text) != null);
        final var items =
            ProposalGenerator.proposeMemberAccess(
                receiverType, injected.prefix(), isStaticAccess, snapshot);
        LOG.fine(
            () ->
                "[completion] proposals count=%d labels=%s"
                    .formatted(
                        items.size(), items.stream().map(CompletionItem::getLabel).toList()));
        return new CompletionOutcome(items, freshAnalysis);
      }
    }

    return CompletionOutcome.of(List.of());
  }
}
