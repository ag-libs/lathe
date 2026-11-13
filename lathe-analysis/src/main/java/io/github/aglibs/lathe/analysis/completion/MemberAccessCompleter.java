package io.github.aglibs.lathe.analysis.completion;

import com.sun.source.tree.Scope;
import io.github.aglibs.lathe.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.analysis.CompletionOutcome;
import io.github.aglibs.lathe.analysis.JavaSourceCompiler;
import io.github.aglibs.lathe.analysis.Positions;
import io.github.aglibs.lathe.analysis.WorkspaceTypeIndex;
import io.github.aglibs.lathe.analysis.internal.SourceTypeNames;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

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
            ? ReceiverResolver.resolveReceiver(parsed, req.pos().getLine(), initialSnapshot)
            : null;

    if (parsed.sentinelContext() == SentinelContext.STATIC_IMPORT
        && initialResolved == null
        && initialSnapshot != null
        && parsed.receiverText() != null) {
      final var candidates =
          new ImportCompletionProvider(
                  initialSnapshot,
                  ReceiverResolver.resolveScope(initialSnapshot, req.cursorOffset()))
              .proposeCandidates(parsed.receiverText(), injected.prefix());
      return new CompletionOutcome(
          candidates.stream().map(CompletionItemPresenter::present).toList(), null);
    }

    final boolean methodChainReceiver = isMethodChainReceiver(parsed);
    final boolean initialUnusable =
        initialResolved != null
            && (initialResolved.type().getKind() == TypeKind.ERROR
                || initialResolved.type().getKind() == TypeKind.TYPEVAR);
    // Expected-value inference reads the snapshot tree at the current cursor offset. When
    // the cached snapshot diverges from the current content before the completion token (e.g. the
    // just-typed `.` is not in the cache yet), that offset is misaligned and can land in an
    // unrelated following expression, wrongly marking the slot value-sensitive (which drops void
    // members). Reattribute so the context is read from the current tree, even when the stale
    // snapshot happens to resolve the receiver.
    final boolean staleBeforeToken = staleBeforeCompletionToken(req, injected);
    final boolean shouldReattribute =
        compiler != null
            && (methodChainReceiver || !req.noDiff())
            && (initialResolved == null || initialUnusable || staleBeforeToken);
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
            ? ReceiverResolver.resolveReceiver(parsed, req.pos().getLine(), reattributedAnalysis)
            : null;
    final var resolved = hasMembers(reattributedResolved) ? reattributedResolved : initialResolved;
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
    final boolean memberReference = parsed.sentinelContext() == SentinelContext.MEMBER_REFERENCE;
    final boolean isStaticAccess =
        parsed.sentinelContext() == SentinelContext.STATIC_IMPORT || resolved.staticAccess();
    final int samArity =
        memberReference
            ? ExpectedValueResolver.methodReferenceSamArity(req.cursorOffset(), snapshot)
            : -1;
    final var scope = ReceiverResolver.resolveScope(snapshot, req.cursorOffset());
    // Method references tolerate void targets and rank by SAM arity — skip the value context, which
    // would drop void members and rank by return type.
    final var semanticContext =
        memberReference
            ? SemanticCompletionContext.blank(snapshot)
            : memberAccessSemanticContext(site, req, parsed, snapshot);
    final var generator = new CandidateGenerator(snapshot);
    final List<CompletionCandidate> members =
        memberReference
            ? methodReferenceMembers(
                generator, resolved, snapshot, injected.prefix(), scope, samArity)
            : generator.proposeMemberAccessCandidates(
                resolved.type(), injected.prefix(), isStaticAccess, scope);
    final Stream<CompletionCandidate> nestedTypes =
        !memberReference
                && isStaticAccess
                && resolved.type() instanceof DeclaredType dt
                && dt.asElement() instanceof TypeElement te
            ? generator.proposeNestedTypes(te, injected.prefix()).stream()
            : Stream.empty();

    final List<CompletionCandidate> candidates =
        Stream.concat(
                Stream.concat(members.stream(), nestedTypes),
                !memberReference && isStaticAccess
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

  // A method reference targets a method (or a constructor via `Type::new`); a type qualifier admits
  // both static and unbound-instance forms, while fields and enum constants are never
  // referenceable.
  private static List<CompletionCandidate> methodReferenceMembers(
      final CandidateGenerator generator,
      final ResolvedReceiver resolved,
      final AttributedFileAnalysis snapshot,
      final String prefix,
      final Scope scope,
      final int samArity) {
    final List<CompletionCandidate> methods =
        generator.proposeMethodReferenceCandidates(
            resolved.type(), prefix, resolved.staticAccess(), scope, samArity);
    final CompletionCandidate constructor =
        constructorReference(resolved, snapshot, prefix, scope, samArity);
    return constructor == null
        ? methods
        : Stream.concat(Stream.of(constructor), methods.stream()).toList();
  }

  // `Type::new` is offered only for a directly-instantiable class or record with a constructor the
  // scope may call: interfaces and abstract classes are excluded, a concrete sealed class is not.
  private static CompletionCandidate constructorReference(
      final ResolvedReceiver resolved,
      final AttributedFileAnalysis snapshot,
      final String prefix,
      final Scope scope,
      final int samArity) {
    if (!resolved.staticAccess()
        || !"new".startsWith(prefix)
        || !(resolved.type() instanceof final DeclaredType declaredType)
        || !(declaredType.asElement() instanceof final TypeElement typeEl)) {
      return null;
    }

    if (!Instantiation.directlyInstantiable(typeEl)) {
      return null;
    }

    final List<ExecutableElement> constructors =
        Instantiation.accessibleConstructors(declaredType, snapshot, scope);
    if (constructors.isEmpty()) {
      return null;
    }

    final String simpleName = typeEl.getSimpleName().toString();
    final String sortText =
        samArity < 0
            ? null
            : "%d_new"
                .formatted(
                    constructors.stream().anyMatch(c -> c.getParameters().size() == samArity)
                        ? 0
                        : 1);
    return new CompletionCandidate(
        "new",
        "new",
        CandidateKind.METHOD_REFERENCE,
        "%s::new".formatted(simpleName),
        "new",
        false,
        sortText,
        declaredType,
        simpleName,
        null);
  }

  private static boolean isMethodChainReceiver(final ParsedSentinel parsed) {
    return parsed.receiverText() != null && parsed.receiverText().indexOf('(') >= 0;
  }

  static boolean staleBeforeCompletionToken(
      final CompletionRequest req, final SentinelInjectionResult injected) {
    final int tokenStart = injected.tokenStart();
    if (req.cached() == null || req.noDiff() || tokenStart < 0) {
      return false;
    }

    // tokenStart is where the completed member name begins (just past the receiver's dot), a raw
    // content offset. The cache is trustworthy for the value-context lookup only if it matches the
    // current content up to there — i.e. the receiver AND the dot are present in the cache. The
    // member-name prefix after tokenStart may differ freely. regionMatches returns false when the
    // cache is shorter than tokenStart.
    return !req.content().regionMatches(0, req.cached().content(), 0, tokenStart);
  }

  // A receiver completion can list members of: a class or interface type, or an array (length,
  // clone() and Object's methods). Only otherwise does the stale snapshot's receiver stand in.
  private static boolean hasMembers(final ResolvedReceiver resolved) {
    return resolved != null
        && (resolved.type().getKind() == TypeKind.DECLARED
            || resolved.type().getKind() == TypeKind.ARRAY);
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

    final var scope = ReceiverResolver.resolveScope(snapshot, cursorOffset);
    final SourceTypeNames names = ReceiverResolver.typeNames(snapshot, scope);

    // An unimported simple name may match several classpath types (e.g. two `Objects`).
    // Offer members from every candidate, each carrying its own import edit, rather than returning
    // only the first match's members.
    final var aggregated = new ArrayList<CompletionItem>();
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
      if (items.isEmpty()) {
        continue;
      }

      applyTypeNameEdits(items, typeEl, lookupName, parsed, injected, names);
      aggregated.addAll(items);
    }

    return CompletionOutcome.of(List.copyOf(aggregated));
  }

  static List<CompletionCandidate> resolvePackageCandidates(
      final AttributedFileAnalysis analysis,
      final int cursorOffset,
      final String receiverText,
      final String prefix) {
    final var scope = ReceiverResolver.resolveScope(analysis, cursorOffset);
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

  // The members of a type the file does not name yet: the receiver's leading name is written as
  // code there names the type (a nested one from a type it can name, Map.Entry, never imported
  // itself), with the import that name needs.
  private static void applyTypeNameEdits(
      final List<CompletionItem> items,
      final TypeElement type,
      final String typedName,
      final ParsedSentinel parsed,
      final SentinelInjectionResult injected,
      final SourceTypeNames names) {
    final List<TextEdit> edits =
        Stream.concat(
                names.importEditsFor(Stream.of(type.asType())).stream(),
                receiverRewrite(names.sourceName(type), typedName, parsed, injected).stream())
            .toList();
    items.forEach(
        item -> edits.forEach(edit -> CompletionItemPresenter.addAdditionalTextEdit(item, edit)));
  }

  // The receiver starts where javac's tree starts it, with the typed name (`Entry` in `Entry` or in
  // `Entry.comparingByKey()`); anything else there is left alone.
  private static Optional<TextEdit> receiverRewrite(
      final String written,
      final String typedName,
      final ParsedSentinel parsed,
      final SentinelInjectionResult injected) {
    final int start = parsed.receiverStartOffset();
    final String content = injected.injectedContent();
    if (written.equals(typedName) || start < 0 || !content.startsWith(typedName, start)) {
      return Optional.empty();
    }

    return Optional.of(
        new TextEdit(
            new Range(
                Positions.offsetToPosition(content, start),
                Positions.offsetToPosition(content, start + typedName.length())),
            written));
  }

  private static SemanticCompletionContext memberAccessSemanticContext(
      final CompletionSite site,
      final CompletionRequest req,
      final ParsedSentinel parsed,
      final AttributedFileAnalysis snapshot) {
    final var base = SemanticCompletionContext.from(site, req, parsed, snapshot);
    final boolean lambdaContext =
        site.sentinelContext() == SentinelContext.LAMBDA_BODY
            || ExpectedValueResolver.isInsideLambdaBody(site.cursorOffset(), snapshot);
    if (base.expectedValue() instanceof final ExpectedValue.Type expectedType
        && lambdaContext
        && ExpectedValueResolver.isVoidFunctionalInterface(expectedType.type(), snapshot)) {
      return base.asStatementContext();
    }

    if (!(base.expectedValue() instanceof ExpectedValue.Unknown)) {
      return base;
    }

    final var outerValue =
        ExpectedValueResolver.resolveExpectedArgumentValue(site.cursorOffset(), snapshot);
    if (outerValue instanceof ExpectedValue.Unknown) {
      return base;
    }

    if (ExpectedValueResolver.isInsideVoidLambdaBody(site.cursorOffset(), snapshot)) {
      return base.asStatementContext();
    }

    if (outerValue instanceof final ExpectedValue.Type expectedType
        && lambdaContext
        && ExpectedValueResolver.isVoidFunctionalInterface(expectedType.type(), snapshot)) {
      return base.asStatementContext();
    }

    return base.withExpectedValue(outerValue);
  }
}
