package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.server.analysis.ImportAnalyzer;
import io.github.aglibs.lathe.server.analysis.JavaSourceCompiler;
import io.github.aglibs.lathe.server.analysis.WorkspaceTypeIndex;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import org.eclipse.lsp4j.CompletionItem;

final class MemberAccessCompleter {

  private static final Logger LOG = Logger.getLogger(MemberAccessCompleter.class.getName());

  private final JavaSourceCompiler compiler;

  MemberAccessCompleter(final JavaSourceCompiler compiler) {
    this.compiler = compiler;
  }

  CompletionOutcome complete(
      final ParsedSentinel parsed,
      final SentinelInjectionResult injected,
      final CompletionRequest req,
      final CompletionSite site) {
    final var initialSnapshot = req.cached() != null ? req.cached().analysis() : null;
    final var initialResolved =
        initialSnapshot != null
            ? TypeResolver.resolveReceiver(parsed, req.pos().getLine(), initialSnapshot)
            : null;

    if (parsed.sentinelContext() == SentinelContext.STATIC_IMPORT
        && initialResolved == null
        && initialSnapshot != null
        && parsed.receiverText() != null) {
      final var candidates =
          new ImportCompletionProvider(
                  initialSnapshot, TypeResolver.resolveScope(initialSnapshot, req.cursorOffset()))
              .proposeCandidates(parsed.receiverText(), injected.prefix());
      return new CompletionOutcome(
          candidates.stream().map(CompletionItemPresenter::present).toList(), null);
    }

    final boolean methodChainReceiver = isMethodChainReceiver(parsed);
    final boolean initialUnusable =
        initialResolved != null
            && (initialResolved.type().getKind() == TypeKind.ERROR
                || initialResolved.type().getKind() == TypeKind.TYPEVAR);
    // CQ-0051: expected-value inference reads the snapshot tree at the current cursor offset. When
    // the cached snapshot diverges from the current content at or before the receiver, that offset
    // is misaligned and can land in an unrelated following expression, wrongly marking the slot
    // value-sensitive (which drops void members). Reattribute so the context is read from the
    // current tree, even when the stale snapshot happens to resolve the receiver.
    final boolean staleReceiverContext = staleBeforeReceiver(req, site);
    final boolean shouldReattribute =
        compiler != null
            && (methodChainReceiver || !req.noDiff())
            && (initialResolved == null || initialUnusable || staleReceiverContext);
    final AttributedFileAnalysis reattributedAnalysis;
    final AttributedFileAnalysis cacheableAnalysis;
    if (shouldReattribute) {
      reattributedAnalysis =
          compiler.reattribute(
              req.uri(), methodChainReceiver ? injected.injectedContent() : req.content());
      cacheableAnalysis = methodChainReceiver ? null : reattributedAnalysis;
    } else {
      reattributedAnalysis = null;
      cacheableAnalysis = null;
    }

    final var reattributedResolved =
        reattributedAnalysis != null
            ? TypeResolver.resolveReceiver(parsed, req.pos().getLine(), reattributedAnalysis)
            : null;
    final var resolved =
        hasDeclaredReceiver(reattributedResolved) ? reattributedResolved : initialResolved;
    final var snapshot =
        (reattributedResolved != null && resolved == reattributedResolved)
                || (resolved == null && cacheableAnalysis != null)
            ? reattributedAnalysis
            : initialSnapshot;

    LOG.fine(
        () ->
            "[completion] resolve receiver=|%s| type=%s static=%s reattributed=%s"
                .formatted(
                    parsed.receiverText(),
                    resolved != null ? resolved.type() : null,
                    resolved != null ? resolved.staticAccess() : null,
                    reattributedResolved != null && resolved == reattributedResolved));

    if (resolved == null) {
      if (snapshot != null && parsed.receiverText() != null) {
        final List<CompletionCandidate> packageCandidates =
            resolvePackageCandidates(
                snapshot, req.cursorOffset(), parsed.receiverText(), injected.prefix());
        if (!packageCandidates.isEmpty()) {
          return CompletionOutcome.of(
              packageCandidates.stream().map(CompletionItemPresenter::present).toList());
        }
      }

      return fallback(parsed, injected, snapshot, req.cursorOffset(), req.typeIndex());
    }

    final CompletionOutcome outcome =
        completeResolved(parsed, injected, req, site, snapshot, resolved, cacheableAnalysis);
    if (!outcome.items().isEmpty()) {
      return outcome;
    }

    if (initialResolved != null && initialResolved != resolved) {
      final CompletionOutcome initialOutcome =
          completeResolved(parsed, injected, req, site, initialSnapshot, initialResolved, null);
      if (!initialOutcome.items().isEmpty()) {
        return initialOutcome;
      }
    }

    if (initialSnapshot != null && initialSnapshot != snapshot) {
      return fallback(parsed, injected, initialSnapshot, req.cursorOffset(), req.typeIndex());
    }

    return fallback(parsed, injected, snapshot, req.cursorOffset(), req.typeIndex());
  }

  private CompletionOutcome completeResolved(
      final ParsedSentinel parsed,
      final SentinelInjectionResult injected,
      final CompletionRequest req,
      final CompletionSite site,
      final AttributedFileAnalysis snapshot,
      final ResolvedReceiver resolved,
      final AttributedFileAnalysis cacheableAnalysis) {
    final boolean isStaticAccess =
        parsed.sentinelContext() == SentinelContext.STATIC_IMPORT || resolved.staticAccess();
    final var scope = TypeResolver.resolveScope(snapshot, req.cursorOffset());
    final var semanticContext = memberAccessSemanticContext(site, req, parsed, snapshot);
    final var generator = new CandidateGenerator(snapshot);
    final var members =
        generator.proposeMemberAccessCandidates(
            resolved.type(), injected.prefix(), isStaticAccess, scope);
    final Stream<CompletionCandidate> nestedTypes =
        isStaticAccess
                && resolved.type() instanceof DeclaredType dt
                && dt.asElement() instanceof TypeElement te
            ? generator.proposeNestedTypes(te, injected.prefix()).stream()
            : Stream.empty();

    final List<CompletionCandidate> candidates =
        Stream.concat(
                Stream.concat(members.stream(), nestedTypes),
                isStaticAccess
                    ? TypeReferenceCompleter.classLiteralCandidates(injected.prefix()).stream()
                    : Stream.of())
            .toList();
    final List<CompletionItem> items =
        CompletionCandidateRanker.rank(candidates, semanticContext).stream()
            .map(CompletionItemPresenter::present)
            .toList();
    LOG.fine(
        () ->
            "[completion] proposals count=%d labels=%s"
                .formatted(items.size(), items.stream().map(CompletionItem::getLabel).toList()));

    if (parsed.sentinelContext() == SentinelContext.STATIC_IMPORT) {
      final var suffix = req.charAfterCursor() == ';' ? "" : ";";
      items.forEach(
          item -> {
            item.setInsertText(item.getFilterText() + suffix);
            item.setInsertTextFormat(null);
          });
    }

    return new CompletionOutcome(items, cacheableAnalysis);
  }

  private static boolean isMethodChainReceiver(final ParsedSentinel parsed) {
    return parsed.receiverText() != null && parsed.receiverText().indexOf('(') >= 0;
  }

  private static boolean staleBeforeReceiver(
      final CompletionRequest req, final CompletionSite site) {
    final int receiverEnd = site.receiverEndOffset();
    if (req.cached() == null || req.noDiff() || receiverEnd < 0) {
      return false;
    }

    // receiverEnd precedes the sentinel injection point, so it is a valid offset into the raw
    // content; the cache is trustworthy for the value-context lookup only if it matches the current
    // content through the receiver. regionMatches returns false when the cache is shorter.
    return !req.content().regionMatches(0, req.cached().content(), 0, receiverEnd);
  }

  private static boolean hasDeclaredReceiver(final ResolvedReceiver resolved) {
    return resolved != null && resolved.type().getKind() == TypeKind.DECLARED;
  }

  private CompletionOutcome fallback(
      final ParsedSentinel parsed,
      final SentinelInjectionResult injected,
      final AttributedFileAnalysis snapshot,
      final int cursorOffset,
      final WorkspaceTypeIndex typeIndex) {
    if (typeIndex == null || snapshot == null || parsed.receiverText() == null) {
      return CompletionOutcome.of(List.of());
    }

    final String receiverText = parsed.receiverText();
    final boolean isMethodChain = receiverText.indexOf('(') >= 0;
    final String lookupName = typeIndexLookupName(receiverText);
    if (lookupName == null) {
      return CompletionOutcome.of(List.of());
    }

    final var scope = TypeResolver.resolveScope(snapshot, cursorOffset);

    for (final var candidate : typeIndex.search(lookupName, 200)) {
      if (!candidate.simpleName().equals(lookupName)) {
        continue;
      }

      final var typeEl = snapshot.elements().getTypeElement(candidate.qualifiedName());
      if (typeEl == null) {
        continue;
      }

      final var generator = new CandidateGenerator(snapshot);
      final var members =
          generator.proposeMemberAccessCandidates(
              typeEl.asType(), injected.prefix(), !isMethodChain, scope);
      final List<CompletionCandidate> candidates =
          isMethodChain
              ? members
              : Stream.concat(
                      members.stream(),
                      generator.proposeNestedTypes(typeEl, injected.prefix()).stream())
                  .toList();

      final List<CompletionItem> items =
          CompletionCandidateRanker.rank(candidates, SemanticCompletionContext.blank(snapshot))
              .stream()
              .map(CompletionItemPresenter::present)
              .toList();
      if (!items.isEmpty()) {
        applyTypeImportEdit(items, candidate.qualifiedName(), snapshot);
        return CompletionOutcome.of(items);
      }
    }

    return CompletionOutcome.of(List.of());
  }

  static List<CompletionCandidate> resolvePackageCandidates(
      final AttributedFileAnalysis analysis,
      final int cursorOffset,
      final String receiverText,
      final String prefix) {
    final var scope = TypeResolver.resolveScope(analysis, cursorOffset);
    return new ImportCompletionProvider(analysis, scope).proposeCandidates(receiverText, prefix);
  }

  private static String typeIndexLookupName(final String receiverText) {
    final int parenIdx = receiverText.indexOf('(');
    final String nameStr;
    if (parenIdx >= 0) {
      final String beforeParen = receiverText.substring(0, parenIdx);
      final int lastDot = beforeParen.lastIndexOf('.');
      nameStr = lastDot >= 0 ? beforeParen.substring(0, lastDot) : beforeParen;
    } else {
      nameStr = receiverText;
    }

    if (nameStr.isEmpty()
        || !Character.isUpperCase(nameStr.charAt(0))
        || nameStr.indexOf('.') >= 0) {
      return null;
    }

    return nameStr;
  }

  private static void applyTypeImportEdit(
      final List<CompletionItem> items,
      final String qualifiedName,
      final AttributedFileAnalysis analysis) {
    final var edit = new ImportAnalyzer(analysis).importEdit(qualifiedName);
    if (edit == null) {
      return;
    }

    items.forEach(item -> CompletionItemPresenter.addAdditionalTextEdit(item, edit));
  }

  private static SemanticCompletionContext memberAccessSemanticContext(
      final CompletionSite site,
      final CompletionRequest req,
      final ParsedSentinel parsed,
      final AttributedFileAnalysis snapshot) {
    final var base = SemanticCompletionContext.from(site, req, parsed, snapshot);
    final boolean lambdaContext =
        site.sentinelContext() == SentinelContext.LAMBDA_BODY
            || TypeResolver.isInsideLambdaBody(site.cursorOffset(), snapshot);
    if (base.expectedValue() instanceof ExpectedValue.Type(final var type)
        && lambdaContext
        && TypeResolver.isVoidFunctionalInterface(type, snapshot)) {
      return base.asStatementContext();
    }

    if (!(base.expectedValue() instanceof ExpectedValue.Unknown)) {
      return base;
    }

    final var outerValue = TypeResolver.resolveExpectedArgumentValue(site.cursorOffset(), snapshot);
    if (outerValue instanceof ExpectedValue.Unknown) {
      return base;
    }

    if (TypeResolver.isInsideVoidLambdaBody(site.cursorOffset(), snapshot)) {
      return base.asStatementContext();
    }

    if (outerValue instanceof ExpectedValue.Type(final var type)
        && lambdaContext
        && TypeResolver.isVoidFunctionalInterface(type, snapshot)) {
      return base.asStatementContext();
    }

    return base.withExpectedValue(outerValue);
  }
}
