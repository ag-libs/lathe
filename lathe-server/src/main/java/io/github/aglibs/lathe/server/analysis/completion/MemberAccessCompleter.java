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

    final var freshAnalysis =
        ((initialResolved == null || initialResolved.type().getKind() == TypeKind.ERROR)
                && compiler != null
                && !req.noDiff())
            ? compiler.reattribute(req.uri(), req.content())
            : null;

    final var snapshot = freshAnalysis != null ? freshAnalysis : initialSnapshot;
    final var resolved =
        freshAnalysis != null
            ? TypeResolver.resolveReceiver(parsed, req.pos().getLine(), freshAnalysis)
            : initialResolved;

    LOG.fine(
        () ->
            "[completion] resolve receiver=|%s| type=%s static=%s reattributed=%s"
                .formatted(
                    parsed.receiverText(),
                    resolved != null ? resolved.type() : null,
                    resolved != null ? resolved.staticAccess() : null,
                    freshAnalysis != null));

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
                    ? CompletionEngine.classLiteralCandidates(injected.prefix()).stream()
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

    return new CompletionOutcome(items, freshAnalysis);
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
    if (!(base.expectedValue() instanceof ExpectedValue.Unknown)) {
      return base;
    }

    final var outerValue = TypeResolver.resolveExpectedArgumentValue(site.cursorOffset(), snapshot);
    if (outerValue instanceof ExpectedValue.Unknown) {
      return base;
    }

    return new SemanticCompletionContext(
        base.analysis(),
        outerValue,
        base.valueContext(),
        base.inEqualityComparison(),
        base.inNonVoidMethod(),
        base.staticMemberResultContext());
  }
}
