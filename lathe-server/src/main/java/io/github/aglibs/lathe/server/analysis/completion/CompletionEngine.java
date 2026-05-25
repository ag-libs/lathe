package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.server.analysis.SourceCompiler;
import io.github.aglibs.lathe.server.analysis.SourceParser;
import io.github.aglibs.lathe.server.analysis.WorkspaceTypeIndex;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.eclipse.lsp4j.CompletionItem;

public final class CompletionEngine {

  private static final Logger LOG = Logger.getLogger(CompletionEngine.class.getName());
  private static final int TYPE_INDEX_RESULT_LIMIT = 50;
  private static final int TYPE_INDEX_VALIDATION_CANDIDATE_LIMIT = 1_000;

  private static final List<String> STATEMENT_KEYWORDS =
      List.of(
          "break",
          "continue",
          "do",
          "else",
          "for",
          "if",
          "new",
          "return",
          "switch",
          "this",
          "throw",
          "try",
          "var",
          "final",
          "while");

  private static final List<String> CLASS_BODY_KEYWORDS =
      List.of(
          "abstract",
          "class",
          "enum",
          "final",
          "interface",
          "private",
          "protected",
          "public",
          "record",
          "static",
          "synchronized",
          "transient",
          "void",
          "volatile");

  private final SentinelParser sentinelParser;
  private final SourceCompiler compiler;
  private final WorkspaceTypeIndex typeIndex;

  public CompletionEngine(
      final SourceParser parser,
      final SourceCompiler compiler,
      final WorkspaceTypeIndex typeIndex) {
    this.sentinelParser = new SentinelParser(parser);
    this.compiler = compiler;
    this.typeIndex = typeIndex;
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
              : completeSimpleNameTypeReference(injected, req);
      case TYPE_REFERENCE -> completeTypeReference(parsed, injected, req);
      case VARIABLE_DECLARATION ->
          parsed.enclosingMethod() == null
              ? completeTypeReference(parsed, injected, req)
              : CompletionOutcome.of(List.of());
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

  private CompletionOutcome completeSimpleName(
      final ParsedSentinel parsed, final SentinelResult injected, final CompletionRequest req) {
    final var javacItems = completeJavacSimpleName(parsed, injected, req);
    final var keywords = keywordsFor(parsed, injected.prefix());
    final var items =
        keywords.isEmpty()
            ? javacItems
            : Stream.concat(javacItems.stream(), keywords.stream()).toList();

    if (!shouldOfferBareTypeReference(injected)) {
      return new CompletionOutcome(items, null);
    }

    return mergeSimpleNameAndTypeIndexItems(items, completeSimpleNameTypeReference(injected, req));
  }

  private static List<CompletionItem> keywordsFor(
      final ParsedSentinel parsed, final String prefix) {
    final List<String> source;
    if (parsed.enclosingMethod() != null) {
      source = STATEMENT_KEYWORDS;
    } else if (parsed.enclosingClass() != null) {
      source = CLASS_BODY_KEYWORDS;
    } else {
      return List.of();
    }

    return source.stream()
        .filter(kw -> kw.startsWith(prefix))
        .map(CompletionItemFactory::keyword)
        .toList();
  }

  private static List<CompletionItem> completeJavacSimpleName(
      final ParsedSentinel parsed, final SentinelResult injected, final CompletionRequest req) {
    if (parsed.enclosingClass() == null
        || req.cached() == null
        || req.cached().analysis() == null) {
      return List.of();
    }

    final var analysis = req.cached().analysis();
    final var expectedParamType =
        TypeResolver.resolveExpectedParamType(parsed, req.pos().getLine(), analysis);
    return new ProposalGenerator(analysis)
        .proposeSimpleName(
            parsed.enclosingClass(),
            parsed.enclosingMethod(),
            injected.prefix(),
            req.cursorOffset(),
            expectedParamType);
  }

  private CompletionOutcome completeTypeReference(
      final ParsedSentinel parsed, final SentinelResult injected, final CompletionRequest req) {
    if (parsed.receiverText() != null) {
      return completeNestedTypes(parsed, injected, req);
    }

    final var typeRefOutcome = completeSimpleNameTypeReference(injected, req);

    if (parsed.enclosingMethod() == null && parsed.enclosingClass() != null) {
      final var keywords = keywordsFor(parsed, injected.prefix());
      if (!keywords.isEmpty()) {
        return mergeSimpleNameAndTypeIndexItems(keywords, typeRefOutcome);
      }
    }

    return typeRefOutcome;
  }

  private static CompletionOutcome completeNestedTypes(
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
            .map(CompletionEngine::typeIndexItem)
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

  private static CompletionItem typeIndexItem(final TypeIndexEntry entry) {
    final var item = new CompletionItem(entry.simpleName());
    item.setInsertText(entry.simpleName());
    item.setFilterText(entry.simpleName());
    item.setDetail(entry.qualifiedName());
    return item;
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
      return CompletionOutcome.of(List.of());
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
    return new CompletionOutcome(items, freshAnalysis);
  }
}
