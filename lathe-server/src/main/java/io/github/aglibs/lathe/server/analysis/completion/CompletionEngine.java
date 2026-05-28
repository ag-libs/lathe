package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.server.analysis.JavaSourceCompiler;
import io.github.aglibs.lathe.server.analysis.SourceParser;
import io.github.aglibs.lathe.server.analysis.WorkspaceTypeIndex;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

public final class CompletionEngine {

  private static final Logger LOG = Logger.getLogger(CompletionEngine.class.getName());
  private static final int TYPE_INDEX_RESULT_LIMIT = 50;
  private static final int TYPE_INDEX_VALIDATION_CANDIDATE_LIMIT = 1_000;

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

    final var outcome =
        switch (parsed.sentinelContext()) {
          case IMPORT -> completeImport(parsed, injected, req);
          case SIMPLE_NAME, ARGUMENT_POSITION -> completeSimpleName(parsed, injected, req);
          case CONSTRUCTOR_CALL ->
              parsed.argIndex() >= 0
                  ? completeSimpleName(parsed, injected, req)
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
    return applyTextEdits(outcome, req.pos(), injected.prefix());
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

    final var items =
        new ImportCompletionProvider(analysis).propose(parsed.receiverText(), injected.prefix());
    final boolean hasSemicolon = req.charAfterCursor() == ';';

    if (!hasSemicolon) {
      items.stream()
          .filter(i -> i.getKind() == CompletionItemKind.Class)
          .forEach(i -> i.setInsertText(i.getLabel() + ";"));
    }

    return new CompletionOutcome(items, req.cached() == null ? analysis : null);
  }

  private CompletionOutcome completeSimpleName(
      final ParsedSentinel parsed, final SentinelResult injected, final CompletionRequest req) {
    final var javacItems = completeJavacSimpleName(parsed, injected, req);
    final var keywords = KeywordProvider.suggest(parsed, injected.prefix(), injected.context());
    final var items =
        keywords.isEmpty()
            ? javacItems
            : Stream.concat(javacItems.stream(), keywords.stream()).toList();

    if (!shouldOfferBareTypeReference(injected)) {
      return new CompletionOutcome(items, null);
    }

    final var typeIndexOutcome = completeSimpleNameTypeReference(injected, req);
    return mergeSimpleNameAndTypeIndexItems(
        items, mergeLangTypes(injected.prefix(), req, typeIndexOutcome));
  }

  private List<CompletionItem> completeJavacSimpleName(
      final ParsedSentinel parsed, final SentinelResult injected, final CompletionRequest req) {
    if (parsed.enclosingClass() == null
        || req.cached() == null
        || req.cached().analysis() == null) {
      return List.of();
    }

    final var analysis = req.cached().analysis();
    final var expectedParamType =
        TypeResolver.resolveExpectedParamType(parsed, req.pos().getLine(), analysis);
    final boolean inValueContext = injected.context() == SentinelInjector.Context.EXPRESSION;
    return new ProposalGenerator(analysis)
        .proposeSimpleName(
            parsed.enclosingClass(),
            parsed.enclosingMethod(),
            injected.prefix(),
            req.cursorOffset(),
            expectedParamType,
            inValueContext);
  }

  private CompletionOutcome completeTypeReference(
      final ParsedSentinel parsed, final SentinelResult injected, final CompletionRequest req) {
    if (parsed.receiverText() != null) {
      return completeNestedTypes(parsed, injected, req);
    }

    final var typeIndexOutcome = completeSimpleNameTypeReference(injected, req);
    final var typeRefOutcome = withLangTypes(parsed, injected, req, typeIndexOutcome);

    if (parsed.enclosingMethod() == null && parsed.enclosingClass() != null) {
      final var keywords = KeywordProvider.suggest(parsed, injected.prefix(), injected.context());
      if (!keywords.isEmpty()) {
        return mergeSimpleNameAndTypeIndexItems(keywords, typeRefOutcome);
      }
    }

    return typeRefOutcome;
  }

  private static CompletionOutcome withLangTypes(
      final ParsedSentinel parsed,
      final SentinelResult injected,
      final CompletionRequest req,
      final CompletionOutcome base) {
    if (parsed.sentinelContext() != SentinelContext.TYPE_REFERENCE) {
      return base;
    }

    return mergeLangTypes(injected.prefix(), req, base);
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
        .map(el -> CompletionItemFactory.typeElement((TypeElement) el))
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

    return new CompletionOutcome(
        new ProposalGenerator(req.cached().analysis()).proposeNestedTypes(outer, injected.prefix()),
        null);
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
    final var items =
        candidates.stream()
            .sorted(typeCandidateComparator(injected.prefix()))
            .filter(validator::isResolvable)
            .limit(TYPE_INDEX_RESULT_LIMIT)
            .map(CompletionItemFactory::typeIndexEntry)
            .toList();
    LOG.fine(() -> "[type-index] typeRef items=%d".formatted(items.size()));
    return CompletionOutcome.incomplete(items);
  }

  private static Comparator<TypeIndexEntry> typeCandidateComparator(final String prefix) {
    return Comparator.comparing((TypeIndexEntry e) -> !e.simpleName().startsWith(prefix))
        .thenComparing(e -> !"java.lang".equals(e.packageName()))
        .thenComparingInt(e -> e.qualifiedName().length())
        .thenComparing(TypeIndexEntry::qualifiedName);
  }

  private static boolean shouldOfferBareTypeReference(final SentinelResult injected) {
    return injected.context() == SentinelInjector.Context.STATEMENT
        && injected.receiverText() == null
        && !injected.prefix().isEmpty()
        && Character.isUpperCase(injected.prefix().charAt(0));
  }

  private static CompletionOutcome mergeSimpleNameAndTypeIndexItems(
      final List<CompletionItem> simpleNameItems, final CompletionOutcome typeIndexOutcome) {
    final var merged = new LinkedHashMap<String, CompletionItem>();
    simpleNameItems.forEach(item -> merged.put(completionIdentity(item), item));
    typeIndexOutcome.items().forEach(item -> merged.putIfAbsent(completionIdentity(item), item));
    return new CompletionOutcome(List.copyOf(merged.values()), null, typeIndexOutcome.incomplete());
  }

  private static CompletionOutcome applyTextEdits(
      final CompletionOutcome outcome, final Position cursor, final String prefix) {
    if (outcome.items().isEmpty()) {
      return outcome;
    }

    final var start = new Position(cursor.getLine(), cursor.getCharacter() - prefix.length());
    final var range = new Range(start, cursor);
    outcome
        .items()
        .forEach(
            item -> {
              final var newText =
                  item.getInsertText() != null ? item.getInsertText() : item.getLabel();
              item.setTextEdit(Either.forLeft(new TextEdit(range, newText)));
            });
    return outcome;
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
      return new CompletionOutcome(
          new ImportCompletionProvider(initialSnapshot)
              .propose(parsed.receiverText(), injected.prefix()),
          null);
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
      return completeMemberAccessTypeIndexFallback(parsed, injected, snapshot, req.cursorOffset());
    }

    final boolean isStaticAccess =
        parsed.sentinelContext() == SentinelContext.STATIC_IMPORT || resolved.staticAccess();
    final var scope = TypeResolver.resolveScope(snapshot, req.cursorOffset());
    final var items =
        new ProposalGenerator(snapshot)
            .proposeMemberAccess(resolved.type(), injected.prefix(), isStaticAccess, scope);
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

      final var items =
          new ProposalGenerator(snapshot)
              .proposeMemberAccess(typeEl.asType(), injected.prefix(), true, scope);
      if (!items.isEmpty()) {
        return CompletionOutcome.of(items);
      }
    }

    return CompletionOutcome.of(List.of());
  }
}
