package io.github.aglibs.lathe.server.analysis.completion;

import com.sun.source.tree.ImportTree;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.server.analysis.JavaSourceCompiler;
import io.github.aglibs.lathe.server.analysis.SourceParser;
import io.github.aglibs.lathe.server.analysis.WorkspaceTypeIndex;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;

public final class CompletionEngine {

  private static final Logger LOG = Logger.getLogger(CompletionEngine.class.getName());
  private static final int TYPE_INDEX_RESULT_LIMIT = 50;
  private static final int TYPE_INDEX_VALIDATION_CANDIDATE_LIMIT = 1_000;
  private static final int STATIC_MEMBER_FIT_LIMIT = 20;

  private final SentinelParser sentinelParser;
  private final JavaSourceCompiler compiler;
  private final WorkspaceTypeIndex typeIndex;

  public CompletionEngine(
      final SourceParser parser,
      final JavaSourceCompiler compiler,
      final WorkspaceTypeIndex typeIndex) {
    this.sentinelParser = new SentinelParser(parser);
    this.compiler = compiler;
    this.typeIndex = typeIndex;
  }

  public CompletionOutcome complete(final CompletionRequest req) {
    final var injected = new SentinelInjector(req.content()).inject(req.cursorOffset());
    LOG.fine(
        () ->
            "[completion] inject prefix=|%s| receiver=|%s| ctx=%s hasDot=%s"
                .formatted(
                    injected.prefix(),
                    injected.receiverText(),
                    injected.context(),
                    injected.hasDot()));

    if (injected.hasDot() && injected.receiverText() == null) {
      LOG.fine(() -> "[completion] bare dot with no receiver — returning empty");
      return CompletionOutcome.of(List.of());
    }

    final int version = req.cached() != null ? req.cached().version() : -1;
    final var parsed = sentinelParser.parse(injected, req.pos().getLine(), version);
    LOG.fine(
        () ->
            "[completion] parsed valid=%s sentinelCtx=%s"
                .formatted(parsed.valid(), parsed.sentinelContext()));

    if (!parsed.valid()) {
      return CompletionOutcome.of(List.of());
    }

    final var site = CompletionSite.from(req, injected, parsed);
    final var outcome =
        switch (parsed.sentinelContext()) {
          case IMPORT -> completeImport(parsed, injected, req);
          case SIMPLE_NAME, ARGUMENT_POSITION -> completeSimpleName(parsed, injected, req, site);
          case CONSTRUCTOR_CALL ->
              parsed.argIndex() >= 0
                  ? completeSimpleName(parsed, injected, req, site)
                  : mergeLangTypes(
                      injected.prefix(), req, completeSimpleNameTypeReference(injected, req));
          case TYPE_REFERENCE -> completeTypeReference(parsed, injected, req);
          case VARIABLE_DECLARATION ->
              parsed.enclosingMethod() == null
                  ? completeTypeReference(parsed, injected, req)
                  : CompletionOutcome.of(List.of());
          case MEMBER_ACCESS, LAMBDA_BODY, STATIC_IMPORT ->
              completeMemberAccess(parsed, injected, req);
          default -> CompletionOutcome.of(List.of());
        };
    CompletionItemPresenter.applyReplacementRange(outcome.items(), site.replacementRange());
    return outcome;
  }

  private CompletionOutcome completeImport(
      final ParsedSentinel parsed, final SentinelResult injected, final CompletionRequest req) {
    final var analysis =
        req.cached() != null
            ? req.cached().analysis()
            : (compiler != null ? compiler.reattribute(req.uri(), req.content()) : null);
    if (analysis == null) {
      return CompletionOutcome.of(List.of());
    }

    final var scope = TypeResolver.resolveScope(analysis, req.cursorOffset());
    final var candidates =
        new ImportCompletionProvider(analysis, scope)
            .proposeCandidates(parsed.receiverText(), injected.prefix());
    final List<CompletionItem> items =
        candidates.stream().map(CompletionItemPresenter::present).toList();
    final boolean hasSemicolon = req.charAfterCursor() == ';';

    if (!hasSemicolon) {
      items.stream()
          .filter(i -> i.getKind() != CompletionItemKind.Module)
          .filter(i -> i.getKind() != CompletionItemKind.Keyword)
          .forEach(i -> i.setInsertText(i.getLabel() + ";"));
    }

    return new CompletionOutcome(items, req.cached() == null ? analysis : null);
  }

  private CompletionOutcome completeSimpleName(
      final ParsedSentinel parsed,
      final SentinelResult injected,
      final CompletionRequest req,
      final CompletionSite site) {
    final var semanticContext = semanticContext(site, req);
    if (semanticContext != null
        && semanticContext.expectedValue() instanceof ExpectedValue.NoSlot) {
      return CompletionOutcome.of(List.of());
    }

    final var javacCandidates = completeJavacSimpleName(parsed, injected, req, semanticContext);
    final var keywordCandidates =
        KeywordProvider.suggestCandidates(parsed, injected.prefix(), injected.context());
    final List<CompletionItem> items =
        presentSimpleNameCandidates(
            Stream.concat(javacCandidates.stream(), keywordCandidates.stream()).toList(),
            semanticContext);

    if (!hasUppercasePrefix(injected)) {
      return new CompletionOutcome(items, null);
    }

    // Type-index types: uppercase prefix in statement context only (avoids flooding arg lists)
    final CompletionOutcome withTypes =
        shouldOfferBareTypeReference(injected)
            ? mergeSimpleNameAndTypeIndexItems(
                items,
                mergeLangTypes(
                    injected.prefix(), req, completeSimpleNameTypeReference(injected, req)))
            : new CompletionOutcome(items, null);

    // Static member fit: uppercase prefix + expected type, works in both statement and expression
    if (semanticContext == null) {
      return withTypes;
    }

    final var staticFitCandidates = staticMemberFitCandidates(injected.prefix(), semanticContext);
    if (staticFitCandidates.isEmpty()) {
      return withTypes;
    }

    final var ranked = CompletionCandidateRanker.rank(staticFitCandidates, semanticContext);
    final List<CompletionItem> staticFitItems =
        ranked.stream().map(CompletionItemPresenter::present).toList();
    CompletionItemPresenter.applyImportEdits(
        ranked.stream().map(RankedCompletionCandidate::candidate).toList(),
        staticFitItems,
        semanticContext.analysis());

    final List<CompletionItem> finalItems =
        Stream.concat(withTypes.items().stream(), staticFitItems.stream())
            .collect(
                Collectors.toMap(
                    CompletionEngine::completionIdentity,
                    i -> i,
                    (existing, ignored) -> existing,
                    LinkedHashMap::new))
            .values()
            .stream()
            .toList();
    return new CompletionOutcome(finalItems, null, withTypes.incomplete());
  }

  private List<CompletionCandidate> completeJavacSimpleName(
      final ParsedSentinel parsed,
      final SentinelResult injected,
      final CompletionRequest req,
      final SemanticCompletionContext semanticContext) {
    if (parsed.enclosingClass() == null || semanticContext == null) {
      return List.of();
    }

    return new CandidateGenerator(semanticContext.analysis())
        .proposeSimpleNameCandidates(
            parsed.enclosingClass(),
            parsed.enclosingMethod(),
            injected.prefix(),
            req.cursorOffset(),
            semanticContext);
  }

  private static List<CompletionItem> presentSimpleNameCandidates(
      final List<CompletionCandidate> candidates, final SemanticCompletionContext semanticContext) {
    if (semanticContext == null) {
      return candidates.stream().map(CompletionItemPresenter::present).toList();
    }

    return CompletionCandidateRanker.rank(candidates, semanticContext).stream()
        .map(CompletionItemPresenter::present)
        .toList();
  }

  private static SemanticCompletionContext semanticContext(
      final CompletionSite site, final CompletionRequest req) {
    if (req.cached() == null || req.cached().analysis() == null) {
      return null;
    }

    return SemanticCompletionContext.from(site, req, req.cached().analysis());
  }

  private CompletionOutcome completeTypeReference(
      final ParsedSentinel parsed, final SentinelResult injected, final CompletionRequest req) {
    if (parsed.receiverText() != null) {
      return completeNestedTypes(parsed, injected, req);
    }

    final var typeIndexOutcome = completeSimpleNameTypeReference(injected, req);
    // Lang types merged only for TYPE_REFERENCE; VARIABLE_DECLARATION arrives here too but
    // gets a bare type-index result.
    final var typeRefOutcome =
        parsed.sentinelContext() == SentinelContext.TYPE_REFERENCE
            ? mergeLangTypes(injected.prefix(), req, typeIndexOutcome)
            : typeIndexOutcome;

    if (parsed.enclosingMethod() == null && parsed.enclosingClass() != null) {
      final List<CompletionItem> keywords =
          KeywordProvider.suggestCandidates(parsed, injected.prefix(), injected.context()).stream()
              .map(CompletionItemPresenter::present)
              .toList();
      if (!keywords.isEmpty()) {
        return mergeSimpleNameAndTypeIndexItems(keywords, typeRefOutcome);
      }
    }

    return typeRefOutcome;
  }

  private static CompletionOutcome mergeLangTypes(
      final String prefix, final CompletionRequest req, final CompletionOutcome base) {
    if (req.cached() == null || req.cached().analysis() == null) {
      return base;
    }

    final var langItems = proposeLangTypes(prefix, req.cached().analysis());
    if (langItems.isEmpty()) {
      return base;
    }

    final var merged = new LinkedHashMap<String, CompletionItem>();
    base.items().forEach(i -> merged.put(completionIdentity(i), i));
    langItems.forEach(i -> merged.putIfAbsent(completionIdentity(i), i));
    return new CompletionOutcome(
        List.copyOf(merged.values()), base.freshAnalysis(), base.incomplete());
  }

  private static List<CompletionItem> proposeLangTypes(
      final String prefix, final AttributedFileAnalysis analysis) {
    return proposeLangTypeCandidates(prefix, analysis).stream()
        .map(CompletionItemPresenter::present)
        .toList();
  }

  private static List<CompletionCandidate> proposeLangTypeCandidates(
      final String prefix, final AttributedFileAnalysis analysis) {
    final var pkg = analysis.elements().getPackageElement("java.lang");
    if (pkg == null) {
      return List.of();
    }

    return pkg.getEnclosedElements().stream()
        .filter(
            el ->
                el.getKind() == ElementKind.CLASS
                    || el.getKind() == ElementKind.INTERFACE
                    || el.getKind() == ElementKind.ENUM
                    || el.getKind() == ElementKind.ANNOTATION_TYPE)
        .filter(el -> !el.getModifiers().contains(Modifier.PRIVATE))
        .filter(el -> el.getSimpleName().toString().startsWith(prefix))
        .map(el -> CandidateFactory.typeElementCandidate((TypeElement) el))
        .toList();
  }

  private CompletionOutcome completeNestedTypes(
      final ParsedSentinel parsed, final SentinelResult injected, final CompletionRequest req) {
    if (req.cached() == null || req.cached().analysis() == null) {
      return CompletionOutcome.of(List.of());
    }

    final var outer = req.cached().analysis().elements().getTypeElement(parsed.receiverText());
    if (outer == null) {
      return CompletionOutcome.of(List.of());
    }

    final var analysis = req.cached().analysis();
    final var candidates =
        new CandidateGenerator(analysis).proposeNestedTypes(outer, injected.prefix());
    final List<CompletionItem> items =
        CompletionCandidateRanker.rank(candidates, memberAccessSemanticContext(analysis)).stream()
            .map(CompletionItemPresenter::present)
            .toList();
    return new CompletionOutcome(items, null);
  }

  private CompletionOutcome completeSimpleNameTypeReference(
      final SentinelResult injected, final CompletionRequest req) {
    if (typeIndex == null || injected.prefix().isEmpty()) {
      return CompletionOutcome.of(List.of());
    }

    final var candidates =
        typeIndex.search(injected.prefix(), TYPE_INDEX_VALIDATION_CANDIDATE_LIMIT);
    final var analysis = req.cached() != null ? req.cached().analysis() : null;
    LOG.fine(
        () ->
            "[type-index] typeRef prefix=|%s| candidates=%d cached=%s"
                .formatted(injected.prefix(), candidates.size(), analysis != null));
    final var validator = new TypeIndexValidator(analysis);
    final List<CompletionCandidate> typeCandidates =
        candidates.stream()
            .sorted(typeCandidateComparator(injected.prefix()))
            .filter(validator::isResolvable)
            .limit(TYPE_INDEX_RESULT_LIMIT)
            .map(CandidateFactory::typeIndexCandidate)
            .toList();
    final List<CompletionItem> items =
        typeCandidates.stream().map(CompletionItemPresenter::present).toList();
    CompletionItemPresenter.applyImportEdits(typeCandidates, items, analysis);
    LOG.fine(() -> "[type-index] typeRef items=%d".formatted(items.size()));
    return CompletionOutcome.incomplete(items);
  }

  private static Comparator<TypeIndexEntry> typeCandidateComparator(final String prefix) {
    return Comparator.comparing((TypeIndexEntry e) -> !e.simpleName().startsWith(prefix))
        .thenComparing(e -> !"java.lang".equals(e.packageName()))
        .thenComparingInt(e -> e.qualifiedName().length())
        .thenComparing(TypeIndexEntry::qualifiedName);
  }

  private static boolean hasUppercasePrefix(final SentinelResult injected) {
    return !injected.prefix().isEmpty()
        && Character.isUpperCase(injected.prefix().charAt(0))
        && injected.receiverText() == null;
  }

  private static boolean shouldOfferBareTypeReference(final SentinelResult injected) {
    return hasUppercasePrefix(injected) && injected.context() == SentinelInjector.Context.STATEMENT;
  }

  private static CompletionOutcome mergeSimpleNameAndTypeIndexItems(
      final List<CompletionItem> simpleNameItems, final CompletionOutcome typeIndexOutcome) {
    final var merged = new LinkedHashMap<String, CompletionItem>();
    simpleNameItems.forEach(item -> merged.put(completionIdentity(item), item));
    typeIndexOutcome.items().forEach(item -> merged.putIfAbsent(completionIdentity(item), item));
    return new CompletionOutcome(List.copyOf(merged.values()), null, typeIndexOutcome.incomplete());
  }

  private static String completionIdentity(final CompletionItem item) {
    return "%s\u0000%s".formatted(item.getLabel(), item.getDetail());
  }

  private CompletionOutcome completeMemberAccess(
      final ParsedSentinel parsed, final SentinelResult injected, final CompletionRequest req) {
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
        (initialResolved == null && compiler != null && !req.noDiff())
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
        final var pkgScope = TypeResolver.resolveScope(snapshot, req.cursorOffset());
        final var packageCandidates =
            new ImportCompletionProvider(snapshot, pkgScope)
                .proposeCandidates(parsed.receiverText(), injected.prefix());
        if (!packageCandidates.isEmpty()) {
          return CompletionOutcome.of(
              packageCandidates.stream().map(CompletionItemPresenter::present).toList());
        }
      }
      return completeMemberAccessTypeIndexFallback(parsed, injected, snapshot, req.cursorOffset());
    }

    final boolean isStaticAccess =
        parsed.sentinelContext() == SentinelContext.STATIC_IMPORT || resolved.staticAccess();
    final var scope = TypeResolver.resolveScope(snapshot, req.cursorOffset());
    final var candidates =
        new CandidateGenerator(snapshot)
            .proposeMemberAccessCandidates(
                resolved.type(), injected.prefix(), isStaticAccess, scope);
    final List<CompletionItem> items =
        CompletionCandidateRanker.rank(candidates, memberAccessSemanticContext(snapshot)).stream()
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

  private CompletionOutcome completeMemberAccessTypeIndexFallback(
      final ParsedSentinel parsed,
      final SentinelResult injected,
      final AttributedFileAnalysis snapshot,
      final int cursorOffset) {
    if (typeIndex == null || snapshot == null || parsed.receiverText() == null) {
      return CompletionOutcome.of(List.of());
    }

    final var scope = TypeResolver.resolveScope(snapshot, cursorOffset);

    for (final var candidate : typeIndex.search(parsed.receiverText(), 200)) {
      if (!candidate.simpleName().equals(parsed.receiverText())) {
        continue;
      }

      final var typeEl = snapshot.elements().getTypeElement(candidate.qualifiedName());
      if (typeEl == null) {
        continue;
      }

      final var candidates =
          new CandidateGenerator(snapshot)
              .proposeMemberAccessCandidates(typeEl.asType(), injected.prefix(), true, scope);
      final List<CompletionItem> items =
          CompletionCandidateRanker.rank(candidates, memberAccessSemanticContext(snapshot)).stream()
              .map(CompletionItemPresenter::present)
              .toList();
      if (!items.isEmpty()) {
        return CompletionOutcome.of(items);
      }
    }

    return CompletionOutcome.of(List.of());
  }

  private List<CompletionCandidate> staticMemberFitCandidates(
      final String prefix, final SemanticCompletionContext context) {
    if (typeIndex == null) {
      return List.of();
    }

    if (!(context.expectedValue() instanceof ExpectedValue.Type(final TypeMirror type))) {
      return List.of();
    }

    final var validator = new TypeIndexValidator(context.analysis());
    final Set<String> existingStaticImports =
        context.analysis().tree() != null
            ? context.analysis().tree().getImports().stream()
                .filter(ImportTree::isStatic)
                .map(imp -> imp.getQualifiedIdentifier().toString())
                .collect(Collectors.toUnmodifiableSet())
            : Set.of();

    final List<CompletionCandidate> result = new ArrayList<>();
    for (final var entry : typeIndex.search(prefix, 200)) {
      if (!validator.isResolvable(entry)) {
        continue;
      }

      final var typeEl = context.analysis().elements().getTypeElement(entry.qualifiedName());
      if (typeEl == null) {
        continue;
      }

      for (final var member : context.analysis().elements().getAllMembers(typeEl)) {
        if (!member.getModifiers().contains(Modifier.PUBLIC)
            || !member.getModifiers().contains(Modifier.STATIC)) {
          continue;
        }

        if (member.getKind() != ElementKind.METHOD && member.getKind() != ElementKind.FIELD) {
          continue;
        }

        final var returnType =
            member.getKind() == ElementKind.METHOD
                ? ((ExecutableElement) member).getReturnType()
                : member.asType();

        if (returnType.getKind() == TypeKind.VOID || returnType.getKind() == TypeKind.TYPEVAR) {
          continue;
        }

        if (!context.analysis().types().isAssignable(returnType, type)) {
          continue;
        }

        final var qualifiedMember = entry.qualifiedName() + "." + member.getSimpleName();
        if (existingStaticImports.contains(qualifiedMember)) {
          continue;
        }

        result.add(staticMemberFitCandidate(member, typeEl, entry.qualifiedName(), context));
        if (result.size() >= STATIC_MEMBER_FIT_LIMIT) {
          return result;
        }
      }
    }

    return result;
  }

  private static CompletionCandidate staticMemberFitCandidate(
      final Element member,
      final TypeElement declaringType,
      final String typeQualifiedName,
      final SemanticCompletionContext context) {
    final var base =
        new CandidateFactory(context.analysis().types())
            .memberCandidate(member, (DeclaredType) declaringType.asType());
    final var typeName = declaringType.getSimpleName().toString();
    final var qualifiedMember = typeQualifiedName + "." + member.getSimpleName();
    return new CompletionCandidate(
        typeName + "." + member.getSimpleName(),
        base.label(),
        base.kind(),
        typeName,
        base.insertText(),
        base.snippet(),
        null,
        base.valueType(),
        typeQualifiedName,
        new ImportEdit(qualifiedMember, true));
  }

  private static SemanticCompletionContext memberAccessSemanticContext(
      final AttributedFileAnalysis snapshot) {
    return new SemanticCompletionContext(snapshot, new ExpectedValue.Unknown(), false);
  }
}
