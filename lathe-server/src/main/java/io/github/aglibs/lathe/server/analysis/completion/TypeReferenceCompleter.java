package io.github.aglibs.lathe.server.analysis.completion;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.Scope;
import com.sun.source.util.TreePathScanner;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.server.analysis.ImportAnalyzer;
import io.github.aglibs.lathe.server.analysis.JavaSourceCompiler;
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
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import org.eclipse.lsp4j.CompletionItem;

final class TypeReferenceCompleter {

  private static final Logger LOG = Logger.getLogger(TypeReferenceCompleter.class.getName());
  private static final int TYPE_INDEX_RESULT_LIMIT = 50;
  private static final int TYPE_INDEX_VALIDATION_CANDIDATE_LIMIT = 1_000;
  private static final Set<String> ACCESS_MODIFIER_KEYWORDS =
      Set.of("public", "private", "protected");
  private static final Set<String> OTHER_MODIFIER_KEYWORDS =
      Set.of("static", "final", "abstract", "synchronized", "transient", "volatile");

  // Ubiquitous JDK packages kept above reactor types in type ranking: without usage statistics
  // (which
  // IDEs use to keep java.util.List etc. on top) a blanket reactor boost would bury these common
  // types under obscure project types. Reactor origin is a tiebreaker below this tier.
  private static final Set<String> COMMON_PLATFORM_PACKAGES =
      Set.of("java.lang", "java.util", "java.io", "java.time", "java.nio", "java.math");

  private final JavaSourceCompiler compiler;

  TypeReferenceCompleter(final JavaSourceCompiler compiler) {
    this.compiler = compiler;
  }

  CompletionOutcome completeTypeReference(
      final ParsedSentinel parsed,
      final SentinelInjectionResult injected,
      final CompletionRequest req) {
    if (parsed.receiverText() != null) {
      final var nestedTypes = completeNestedTypes(parsed, injected, req);
      if (!nestedTypes.items().isEmpty() || !typeLikeReceiver(parsed.receiverText())) {
        return nestedTypes;
      }

      return CompletionOutcome.of(
          classLiteralCandidates(injected.prefix()).stream()
              .map(CompletionItemPresenter::present)
              .toList());
    }

    final var typeIndexOutcome =
        completeSimpleNameTypeReference(injected, req, parsed.typeReferenceRole());
    // Lang types merged only for TYPE_REFERENCE; VARIABLE_DECLARATION arrives here too but
    // gets a bare type-index result.
    final var typeRefOutcome =
        parsed.sentinelContext() == SentinelContext.TYPE_REFERENCE
            ? mergeLangTypes(injected.prefix(), req, typeIndexOutcome, parsed.typeReferenceRole())
            : typeIndexOutcome;

    if (parsed.typeReferenceRole() == TypeReferenceRole.ORDINARY
        && parsed.enclosingClass() != null) {
      final List<CompletionItem> keywords =
          KeywordProvider.suggestCandidates(parsed, injected.prefix(), injected.context()).stream()
              .filter(
                  candidate ->
                      parsed.enclosingMethod() != null
                          || classBodyModifierAllows(candidate, req, injected.tokenStart()))
              .map(CompletionItemPresenter::present)
              .toList();
      if (!keywords.isEmpty()) {
        return mergeSimpleNameAndTypeIndexItems(keywords, typeRefOutcome);
      }
    }

    return typeRefOutcome;
  }

  CompletionOutcome completeConstructorTypeReference(
      final SentinelInjectionResult injected,
      final CompletionRequest req,
      final ParsedSentinel parsed,
      final CompletionSite site) {
    final var role = parsed.typeReferenceRole();
    final CompletionOutcome base = completeSimpleNameTypeReferenceWithLang(injected, req, role);
    final AttributedFileAnalysis initialAnalysis =
        req.cached() != null ? req.cached().analysis() : null;
    final List<CompletionCandidate> initialExpected =
        expectedConstructorTypeCandidates(
            injected.prefix(),
            req.cursorOffset(),
            initialAnalysis,
            initialAnalysis != null
                ? SemanticCompletionContext.from(site, req, parsed, initialAnalysis)
                : null,
            req.typeIndex());
    final AttributedFileAnalysis freshAnalysis =
        initialExpected.isEmpty() && compiler != null && !req.noDiff()
            ? compiler.reattribute(req.uri(), req.content())
            : null;
    final AttributedFileAnalysis analysis = freshAnalysis != null ? freshAnalysis : initialAnalysis;
    final List<CompletionCandidate> expected =
        freshAnalysis != null
            ? expectedConstructorTypeCandidates(
                injected.prefix(),
                req.cursorOffset(),
                freshAnalysis,
                SemanticCompletionContext.from(site, req, parsed, freshAnalysis),
                req.typeIndex())
            : initialExpected;
    if (expected.isEmpty()) {
      return incompleteEmptyConstructorPrefix(injected, base);
    }

    final List<CompletionItem> expectedItems =
        expected.stream().map(CompletionItemPresenter::present).toList();
    CompletionItemPresenter.applyImportEdits(expected, expectedItems, analysis);

    return mergeOutcomes(
        expectedItems,
        base.items(),
        freshAnalysis != null ? freshAnalysis : base.freshAnalysis(),
        base.incomplete());
  }

  CompletionOutcome completeSimpleNameTypeReferenceWithLang(
      final SentinelInjectionResult injected,
      final CompletionRequest req,
      final TypeReferenceRole role) {
    final var typeIndexOutcome = completeSimpleNameTypeReference(injected, req, role);
    final var withLang = mergeLangTypes(injected.prefix(), req, typeIndexOutcome, role);
    return mergeInFileTypes(injected.prefix(), req, withLang, role);
  }

  static List<CompletionCandidate> classLiteralCandidates(final String prefix) {
    if (!"class".startsWith(prefix)) {
      return List.of();
    }

    return List.of(
        new CompletionCandidate(
            "class",
            "class",
            CandidateKind.KEYWORD,
            "Class",
            "class",
            false,
            "8_class",
            null,
            null,
            null));
  }

  static CompletionOutcome mergeSimpleNameAndTypeIndexItems(
      final List<CompletionItem> simpleNameItems, final CompletionOutcome typeIndexOutcome) {
    return mergeOutcomes(
        simpleNameItems, typeIndexOutcome.items(), null, typeIndexOutcome.incomplete());
  }

  static String completionIdentity(final CompletionItem item) {
    return "%s\u0000%s".formatted(item.getLabel(), item.getDetail());
  }

  private static CompletionOutcome completeSimpleNameTypeReference(
      final SentinelInjectionResult injected,
      final CompletionRequest req,
      final TypeReferenceRole role) {
    if (req.typeIndex() == null || injected.prefix().isEmpty()) {
      return CompletionOutcome.of(List.of());
    }

    final var candidates =
        req.typeIndex().search(injected.prefix(), TYPE_INDEX_VALIDATION_CANDIDATE_LIMIT);
    final var analysis = req.cached() != null ? req.cached().analysis() : null;
    LOG.fine(
        () ->
            "[type-index] typeRef prefix=|%s| candidates=%d cached=%s"
                .formatted(injected.prefix(), candidates.size(), analysis != null));
    final var scope =
        analysis != null ? TypeResolver.resolveScope(analysis, req.cursorOffset()) : null;
    final var validator = new TypeIndexValidator(analysis, scope);
    final List<CompletionCandidate> typeCandidates =
        candidates.stream()
            .sorted(typeCandidateComparator(injected.prefix(), req.typeIndex()))
            .filter(validator::isResolvable)
            .filter(entry -> typeIndexRoleAllows(entry, analysis, scope, role))
            .limit(TYPE_INDEX_RESULT_LIMIT)
            .map(CandidateFactory::typeIndexCandidate)
            .toList();
    final List<CompletionItem> items =
        typeCandidates.stream().map(CompletionItemPresenter::present).toList();
    CompletionItemPresenter.applyImportEdits(typeCandidates, items, analysis);
    LOG.fine(() -> "[type-index] typeRef items=%d".formatted(items.size()));
    return CompletionOutcome.incomplete(items);
  }

  private static CompletionOutcome completeNestedTypes(
      final ParsedSentinel parsed,
      final SentinelInjectionResult injected,
      final CompletionRequest req) {
    if (req.cached() == null || req.cached().analysis() == null) {
      return CompletionOutcome.of(List.of());
    }

    final var analysis = req.cached().analysis();
    final var outer = analysis.elements().getTypeElement(parsed.receiverText());
    if (outer == null) {
      final List<CompletionCandidate> packageCandidates =
          MemberAccessCompleter.resolvePackageCandidates(
              analysis, req.cursorOffset(), parsed.receiverText(), injected.prefix());
      return CompletionOutcome.of(
          packageCandidates.stream().map(CompletionItemPresenter::present).toList());
    }

    final var candidates =
        new CandidateGenerator(analysis).proposeNestedTypes(outer, injected.prefix());
    final List<CompletionItem> items =
        CompletionCandidateRanker.rank(candidates, SemanticCompletionContext.blank(analysis))
            .stream()
            .map(CompletionItemPresenter::present)
            .toList();
    return new CompletionOutcome(items, null);
  }

  private static boolean typeLikeReceiver(final String receiverText) {
    final int dot = receiverText.lastIndexOf('.');
    final String simpleName = dot >= 0 ? receiverText.substring(dot + 1) : receiverText;
    return !simpleName.isEmpty() && Character.isUpperCase(simpleName.codePointAt(0));
  }

  private static boolean classBodyModifierAllows(
      final CompletionCandidate candidate, final CompletionRequest req, final int tokenStart) {
    if (candidate.kind() != CandidateKind.KEYWORD) {
      return true;
    }

    final var modifiers = classBodyModifiersBefore(req.content(), tokenStart);
    if (modifiers.isEmpty()) {
      return true;
    }

    final String keyword = candidate.name();
    return switch (keyword) {
      case "public", "private", "protected" ->
          !modifiers.contains(keyword)
              && modifiers.stream().noneMatch(ACCESS_MODIFIER_KEYWORDS::contains);
      case "final" ->
          !modifiers.contains("final")
              && !modifiers.contains("abstract")
              && noFinalCombination(modifiers);
      case "abstract", "volatile" ->
          !modifiers.contains(keyword)
              && !modifiers.contains("final")
              && noFinalCombination(modifiers);
      case "static", "synchronized", "transient" ->
          !modifiers.contains(keyword) && noFinalCombination(modifiers);
      default -> true;
    };
  }

  private static boolean noFinalCombination(final Set<String> modifiers) {
    return !modifiers.contains("final") || modifiers.size() == 1;
  }

  private static Set<String> classBodyModifiersBefore(final String content, final int tokenStart) {
    final int lineStart = content.lastIndexOf('\n', Math.max(0, tokenStart - 1)) + 1;
    return Stream.of(content.substring(lineStart, tokenStart).trim().split("\\s+"))
        .filter(token -> !token.isBlank())
        .filter(
            token ->
                ACCESS_MODIFIER_KEYWORDS.contains(token) || OTHER_MODIFIER_KEYWORDS.contains(token))
        .collect(Collectors.toUnmodifiableSet());
  }

  private static CompletionOutcome mergeLangTypes(
      final String prefix,
      final CompletionRequest req,
      final CompletionOutcome base,
      final TypeReferenceRole role) {
    if (req.cached() == null || req.cached().analysis() == null) {
      return base;
    }

    final var langItems =
        proposeLangTypes(prefix, req.cached().analysis(), req.cursorOffset(), role);
    if (langItems.isEmpty()) {
      return base;
    }

    return mergeOutcomes(base.items(), langItems, base.freshAnalysis(), base.incomplete());
  }

  private static List<CompletionItem> proposeLangTypes(
      final String prefix,
      final AttributedFileAnalysis analysis,
      final int cursorOffset,
      final TypeReferenceRole role) {
    return proposeLangTypeCandidates(prefix, analysis, cursorOffset, role).stream()
        .map(CompletionItemPresenter::present)
        .toList();
  }

  private static List<CompletionCandidate> proposeLangTypeCandidates(
      final String prefix,
      final AttributedFileAnalysis analysis,
      final int cursorOffset,
      final TypeReferenceRole role) {
    final var pkg = analysis.elements().getPackageElement("java.lang");
    if (pkg == null) {
      return List.of();
    }

    final var scope = TypeResolver.resolveScope(analysis, cursorOffset);
    return pkg.getEnclosedElements().stream()
        .filter(
            el ->
                el.getKind() == ElementKind.CLASS
                    || el.getKind() == ElementKind.INTERFACE
                    || el.getKind() == ElementKind.ENUM
                    || el.getKind() == ElementKind.ANNOTATION_TYPE)
        .filter(el -> !el.getModifiers().contains(Modifier.PRIVATE))
        .filter(el -> el.getSimpleName().toString().startsWith(prefix))
        .filter(el -> typeReferenceRoleAllows((TypeElement) el, analysis, scope, role))
        .map(el -> CandidateFactory.typeElementCandidate((TypeElement) el))
        .toList();
  }

  private CompletionOutcome mergeInFileTypes(
      final String prefix,
      final CompletionRequest req,
      final CompletionOutcome base,
      final TypeReferenceRole role) {
    if (req.cached() == null || req.cached().analysis() == null) {
      return base;
    }

    final List<CompletionCandidate> candidates =
        proposeInFileTypeCandidates(prefix, req.cached().analysis(), req.cursorOffset(), role);
    if (candidates.isEmpty()) {
      return base;
    }

    final List<CompletionItem> inFileItems =
        candidates.stream().map(CompletionItemPresenter::present).toList();
    return mergeOutcomes(base.items(), inFileItems, base.freshAnalysis(), base.incomplete());
  }

  private static List<CompletionCandidate> proposeInFileTypeCandidates(
      final String prefix,
      final AttributedFileAnalysis analysis,
      final int cursorOffset,
      final TypeReferenceRole role) {
    if (analysis.tree() == null) {
      return List.of();
    }

    final var scope = TypeResolver.resolveScope(analysis, cursorOffset);
    final var result = new ArrayList<CompletionCandidate>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitClass(final ClassTree node, final Void unused) {
        final var el = analysis.trees().getElement(getCurrentPath());
        if (el instanceof final TypeElement te
            && te.getSimpleName().toString().startsWith(prefix)
            && typeReferenceRoleAllows(te, analysis, scope, role)) {
          result.add(CandidateFactory.typeElementCandidate(te));
        }

        return super.visitClass(node, unused);
      }
    }.scan(analysis.tree(), null);
    return result;
  }

  private static List<CompletionCandidate> expectedConstructorTypeCandidates(
      final String prefix,
      final int cursorOffset,
      final AttributedFileAnalysis analysis,
      final SemanticCompletionContext semanticContext,
      final WorkspaceTypeIndex typeIndex) {
    if (analysis == null) {
      return List.of();
    }

    final ExpectedValue expected =
        semanticContext != null && semanticContext.expectedValue() instanceof ExpectedValue.Type
            ? semanticContext.expectedValue()
            : TypeResolver.resolveExpectedArgumentValue(cursorOffset, analysis);
    if (!(expected instanceof ExpectedValue.Type(final TypeMirror type))) {
      return List.of();
    }

    if (!(analysis.types().asElement(type) instanceof final TypeElement expectedEl)) {
      return List.of();
    }

    final var scope = TypeResolver.resolveScope(analysis, cursorOffset);
    final List<CompletionCandidate> subtypeCandidates =
        instantiableSubtypeCandidates(prefix, expectedEl, analysis, typeIndex, scope);
    if (!(expectedEl.getSimpleName().toString().startsWith(prefix)
        && constructibleType(expectedEl, analysis, null))) {
      return subtypeCandidates;
    }

    return Stream.concat(
            Stream.of(
                CandidateFactory.typeElementCandidate(
                    expectedEl, importEdit(expectedEl, analysis))),
            subtypeCandidates.stream())
        .toList();
  }

  private static List<CompletionCandidate> instantiableSubtypeCandidates(
      final String prefix,
      final TypeElement expectedEl,
      final AttributedFileAnalysis analysis,
      final WorkspaceTypeIndex typeIndex,
      final Scope scope) {
    if (typeIndex == null) {
      return List.of();
    }

    final var binaryName = analysis.elements().getBinaryName(expectedEl).toString();
    return typeIndex.transitiveSubtypes(binaryName).stream()
        .filter(TypeReferenceCompleter::indexKindMaybeInstantiable)
        .filter(entry -> entry.simpleName().startsWith(prefix))
        .sorted(typeCandidateComparator(prefix, typeIndex))
        .limit(TYPE_INDEX_RESULT_LIMIT)
        .filter(entry -> resolvesToInstantiableSubtype(entry, expectedEl, analysis, scope))
        .map(CandidateFactory::typeIndexCandidate)
        .toList();
  }

  private static boolean resolvesToInstantiableSubtype(
      final TypeIndexEntry entry,
      final TypeElement expectedEl,
      final AttributedFileAnalysis analysis,
      final Scope scope) {
    final var subtypeEl = analysis.elements().getTypeElement(entry.qualifiedName());
    return subtypeEl != null
        && !subtypeEl.equals(expectedEl)
        && directlyInstantiable(subtypeEl)
        && (scope == null || analysis.trees().isAccessible(scope, subtypeEl));
  }

  private static boolean indexKindMaybeInstantiable(final TypeIndexEntry entry) {
    return entry.kind() == TypeKind.CLASS || entry.kind() == TypeKind.RECORD;
  }

  private static boolean directlyInstantiable(final TypeElement typeEl) {
    return (typeEl.getKind() == ElementKind.CLASS || typeEl.getKind() == ElementKind.RECORD)
        && !typeEl.getModifiers().contains(Modifier.ABSTRACT)
        && !typeEl.getModifiers().contains(Modifier.SEALED);
  }

  private static CompletionOutcome incompleteEmptyConstructorPrefix(
      final SentinelInjectionResult injected, final CompletionOutcome base) {
    return injected.prefix().isEmpty()
        ? new CompletionOutcome(base.items(), base.freshAnalysis(), true)
        : base;
  }

  private static ImportEdit importEdit(
      final TypeElement typeElement, final AttributedFileAnalysis analysis) {
    final String qualifiedName = typeElement.getQualifiedName().toString();
    return new ImportAnalyzer(analysis).needsImport(qualifiedName)
        ? new ImportEdit(qualifiedName, false)
        : null;
  }

  private static boolean typeIndexRoleAllows(
      final TypeIndexEntry entry,
      final AttributedFileAnalysis analysis,
      final Scope scope,
      final TypeReferenceRole role) {
    if (analysis == null || role == TypeReferenceRole.ORDINARY) {
      return true;
    }

    final var typeEl = analysis.elements().getTypeElement(entry.qualifiedName());
    return typeEl == null || typeReferenceRoleAllows(typeEl, analysis, scope, role);
  }

  private static boolean typeReferenceRoleAllows(
      final TypeElement typeEl,
      final AttributedFileAnalysis analysis,
      final Scope scope,
      final TypeReferenceRole role) {
    return switch (role) {
      case ORDINARY -> true;
      case CONSTRUCTOR -> constructibleType(typeEl, analysis, scope);
      case CLASS_EXTENDS -> extendableClass(typeEl);
      case CLASS_IMPLEMENTS, INTERFACE_EXTENDS, RECORD_IMPLEMENTS ->
          typeEl.getKind() == ElementKind.INTERFACE;
      case THROWS -> throwableType(typeEl, analysis);
      case ANNOTATION -> typeEl.getKind() == ElementKind.ANNOTATION_TYPE;
    };
  }

  private static boolean constructibleType(
      final TypeElement typeEl, final AttributedFileAnalysis analysis, final Scope scope) {
    if (typeEl.getKind() == ElementKind.INTERFACE) {
      return !typeEl.getModifiers().contains(Modifier.SEALED);
    }

    if (typeEl.getKind() != ElementKind.CLASS && typeEl.getKind() != ElementKind.RECORD) {
      return false;
    }

    if (typeEl.getModifiers().contains(Modifier.SEALED)
        && typeEl.getModifiers().contains(Modifier.ABSTRACT)) {
      return false;
    }

    final var declaredType = (DeclaredType) typeEl.asType();
    return typeEl.getEnclosedElements().stream()
        .filter(el -> el.getKind() == ElementKind.CONSTRUCTOR)
        .anyMatch(el -> accessibleConstructor(el, declaredType, analysis, scope));
  }

  private static boolean accessibleConstructor(
      final Element el,
      final DeclaredType declaredType,
      final AttributedFileAnalysis analysis,
      final Scope scope) {
    if (scope == null) {
      return true;
    }

    try {
      return analysis.trees().isAccessible(scope, el, declaredType);
    } catch (final IllegalArgumentException ignored) {
      return true;
    }
  }

  private static boolean extendableClass(final TypeElement typeEl) {
    return typeEl.getKind() == ElementKind.CLASS && !typeEl.getModifiers().contains(Modifier.FINAL);
  }

  private static boolean throwableType(
      final TypeElement typeEl, final AttributedFileAnalysis analysis) {
    final var throwable = analysis.elements().getTypeElement("java.lang.Throwable");
    return throwable != null && analysis.types().isAssignable(typeEl.asType(), throwable.asType());
  }

  private static Comparator<TypeIndexEntry> typeCandidateComparator(
      final String prefix, final WorkspaceTypeIndex typeIndex) {
    return Comparator.comparing((TypeIndexEntry e) -> !e.simpleName().startsWith(prefix))
        .thenComparingInt(e -> originRank(e, typeIndex))
        .thenComparingInt(e -> e.qualifiedName().length())
        .thenComparing(TypeIndexEntry::qualifiedName);
  }

  // Lower ranks sort first: core java.lang, then other ubiquitous platform packages, then
  // reactor-local types, then everything else (dependency / other JDK). Reactor origin is a
  // tiebreaker beneath match quality and the common-platform tier — without the usage statistics
  // IDEs rely on, keeping java.* ubiquity on top avoids burying it under obscure project types.
  private static int originRank(final TypeIndexEntry entry, final WorkspaceTypeIndex typeIndex) {
    final String packageName = entry.packageName();
    if ("java.lang".equals(packageName)) {
      return 0;
    }

    // Exact match only: subpackages like java.lang.management or java.util.spi are specialized, not
    // the auto-imported / everyday types, so they rank as ordinary JDK entries (below reactor).
    if (COMMON_PLATFORM_PACKAGES.contains(packageName)) {
      return 1;
    }

    if (isReactorEntry(entry, typeIndex)) {
      return 2;
    }

    return 3;
  }

  private static boolean isReactorEntry(
      final TypeIndexEntry entry, final WorkspaceTypeIndex typeIndex) {
    return typeIndex != null && typeIndex.isReactorType(entry.binaryName());
  }

  private static CompletionOutcome mergeOutcomes(
      final List<CompletionItem> primary,
      final List<CompletionItem> secondary,
      final AttributedFileAnalysis freshAnalysis,
      final boolean incomplete) {
    if (secondary.isEmpty()) {
      return new CompletionOutcome(primary, freshAnalysis, incomplete);
    }

    final var merged = new LinkedHashMap<String, CompletionItem>();
    primary.forEach(i -> merged.put(completionIdentity(i), i));
    secondary.forEach(i -> merged.putIfAbsent(completionIdentity(i), i));
    return new CompletionOutcome(List.copyOf(merged.values()), freshAnalysis, incomplete);
  }
}
