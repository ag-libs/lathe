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

  private static int skipBackWhitespace(final String content, final int dotPos) {
    int i = dotPos - 1;
    while (i >= 0 && Character.isWhitespace(content.charAt(i))) {
      i--;
    }

    return i + 1;
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

    if (!parsed.valid()) {
      return CompletionOutcome.of(List.of());
    }

    return switch (parsed.sentinelContext()) {
      case IMPORT -> completeImport(parsed, injected, req);
      case SIMPLE_NAME, ARGUMENT_POSITION -> completeSimpleName(parsed, injected, req);
      case CONSTRUCTOR_CALL ->
          req.charBeforePrefix() == '('
              ? completeSimpleName(parsed, injected, req)
              : CompletionOutcome.of(List.of());
      case TYPE_REFERENCE -> completeTypeReference(parsed, injected, req);
      case MEMBER_ACCESS, LAMBDA_BODY, STATIC_IMPORT -> completeMemberAccess(parsed, injected, req);
      default -> CompletionOutcome.of(List.of());
    };
  }

  private CompletionOutcome completeImport(
      final ParsedSentinel parsed, final SentinelResult injected, final CompletionRequest req) {
    if (parsed.receiverText() == null) {
      return CompletionOutcome.of(List.of());
    }

    final var analysis =
        req.cached() != null
            ? req.cached().analysis()
            : (compiler != null ? compiler.reattribute(req.uri(), req.content()) : null);
    if (analysis == null) {
      return CompletionOutcome.of(List.of());
    }

    return new CompletionOutcome(
        new ImportCompletionProvider(analysis).propose(parsed.receiverText(), injected.prefix()),
        req.cached() == null ? analysis : null);
  }

  private static CompletionOutcome completeSimpleName(
      final ParsedSentinel parsed, final SentinelResult injected, final CompletionRequest req) {
    if (parsed.enclosingClass() == null
        || req.cached() == null
        || req.cached().analysis() == null) {
      return CompletionOutcome.of(List.of());
    }

    return new CompletionOutcome(
        new ProposalGenerator(req.cached().analysis())
            .proposeSimpleName(
                parsed.enclosingClass(),
                parsed.enclosingMethod(),
                injected.prefix(),
                req.cursorOffset()),
        null);
  }

  private static CompletionOutcome completeTypeReference(
      final ParsedSentinel parsed, final SentinelResult injected, final CompletionRequest req) {
    if (parsed.receiverText() == null || req.cached() == null || req.cached().analysis() == null) {
      return CompletionOutcome.of(List.of());
    }

    final var outer = req.cached().analysis().elements().getTypeElement(parsed.receiverText());
    if (outer == null) {
      return CompletionOutcome.of(List.of());
    }

    return new CompletionOutcome(
        new ProposalGenerator(req.cached().analysis()).proposeNestedTypes(outer, injected.prefix()),
        null);
  }

  private CompletionOutcome completeMemberAccess(
      final ParsedSentinel parsed, final SentinelResult injected, final CompletionRequest req) {
    final int rawDot = injected.receiverText() != null ? injected.tokenStart() - 1 : -1;
    final int dotOffset = rawDot > 0 ? skipBackWhitespace(req.content(), rawDot) : rawDot;
    final var initialSnapshot = req.cached() != null ? req.cached().analysis() : null;
    final var initialType =
        initialSnapshot != null
            ? TypeResolver.resolveReceiverType(
                parsed, req.pos().getLine(), dotOffset, initialSnapshot)
            : null;

    if (parsed.sentinelContext() == SentinelContext.STATIC_IMPORT
        && initialType == null
        && initialSnapshot != null
        && parsed.receiverText() != null) {
      return new CompletionOutcome(
          new ImportCompletionProvider(initialSnapshot)
              .propose(parsed.receiverText(), injected.prefix()),
          null);
    }

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

    if (receiverType == null) {
      return CompletionOutcome.of(List.of());
    }

    final var text = parsed.receiverText();
    final boolean isStaticAccess =
        parsed.sentinelContext() == SentinelContext.STATIC_IMPORT
            || (text != null
                && text.indexOf('(') < 0
                && (text.indexOf('.') < 0
                    ? Character.isUpperCase(text.charAt(0))
                    : snapshot.elements().getTypeElement(text) != null));
    final var scope = TypeResolver.resolveScope(snapshot, req.cursorOffset());
    final var items =
        new ProposalGenerator(snapshot)
            .proposeMemberAccess(receiverType, injected.prefix(), isStaticAccess, scope);
    LOG.fine(
        () ->
            "[completion] proposals count=%d labels=%s"
                .formatted(items.size(), items.stream().map(CompletionItem::getLabel).toList()));
    return new CompletionOutcome(items, freshAnalysis);
  }
}
