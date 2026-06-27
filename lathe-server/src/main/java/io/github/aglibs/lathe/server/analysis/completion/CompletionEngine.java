package io.github.aglibs.lathe.server.analysis.completion;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.Scope;
import com.sun.source.util.TreePathScanner;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.server.analysis.ImportAnalyzer;
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
  private static final Set<String> ACCESS_MODIFIER_KEYWORDS =
      Set.of("public", "private", "protected");
  private static final Set<String> OTHER_MODIFIER_KEYWORDS =
      Set.of("static", "final", "abstract", "synchronized", "transient", "volatile");

  private final SentinelParser sentinelParser;
  private final JavaSourceCompiler compiler;

  public CompletionEngine(final SourceParser parser, final JavaSourceCompiler compiler) {
    this.sentinelParser = new SentinelParser(parser);
    this.compiler = compiler;
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
            "[completion] parsed valid=%s sentinelCtx=%s receiver=|%s| class=%s method=%s role=%s"
                .formatted(
                    parsed.valid(),
                    parsed.sentinelContext(),
                    parsed.receiverText(),
                    parsed.enclosingClass(),
                    parsed.enclosingMethod(),
                    parsed.typeReferenceRole()));

    if (!parsed.valid()) {
      return CompletionOutcome.of(List.of());
    }

    final var site = CompletionSite.from(req, injected, parsed);
    final var outcome =
        switch (parsed.sentinelContext()) {
          case IMPORT -> completeImport(parsed, injected, req);
          case SIMPLE_NAME, ARGUMENT_POSITION, CASE_LABEL ->
              completeSimpleName(parsed, injected, req, site);
          case CONSTRUCTOR_CALL ->
              parsed.argIndex() >= 0
                  ? completeSimpleName(parsed, injected, req, site)
                  : completeConstructorTypeReference(injected, req, parsed, site);
          case TYPE_REFERENCE ->
              shouldTreatTypeReferenceAsMixedSimpleName(parsed, injected)
                  ? completeSimpleName(parsed, injected, req, site)
                  : completeTypeReference(parsed, injected, req);
          case ANNOTATION_CONTEXT ->
              completeSimpleNameTypeReferenceWithLang(injected, req, parsed.typeReferenceRole());
          case ANNOTATION_ARGUMENT -> completeAnnotationArgument(parsed, injected, req);
          case ANNOTATION_ARGUMENT_VALUE -> completeAnnotationArgumentValue(parsed, injected, req);
          case VARIABLE_DECLARATION ->
              isRealNameSlot(parsed)
                  ? CompletionOutcome.of(List.of())
                  : (parsed.enclosingMethod() == null
                      ? completeTypeReference(parsed, injected, req)
                      : CompletionOutcome.of(List.of()));
          case MEMBER_ACCESS, STATIC_IMPORT -> completeMemberAccess(parsed, injected, req, site);
          case LAMBDA_BODY ->
              parsed.receiverText() != null
                  ? completeMemberAccess(parsed, injected, req, site)
                  : completeSimpleName(parsed, injected, req, site);
          default -> CompletionOutcome.of(List.of());
        };
    CompletionItemPresenter.applyReplacementRange(outcome.items(), site.replacementRange());
    CompletionEditApplier.preserveExistingMethodCall(outcome.items(), req, injected.tokenEnd());
    final var analysis =
        outcome.freshAnalysis() != null ? outcome.freshAnalysis() : resolveAnalysis(req);
    CompletionEditApplier.applyNestedOuterImportEdits(
        outcome.items(), injected.receiverText(), analysis);
    CompletionEditApplier.applyStatementSemicolonEdits(
        outcome.items(), site, analysis, req, injected.tokenStart());
    return outcome;
  }

  private CompletionOutcome completeImport(
      final ParsedSentinel parsed,
      final SentinelInjectionResult injected,
      final CompletionRequest req) {
    final AttributedFileAnalysis analysis = resolveAnalysis(req);
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
      final SentinelInjectionResult injected,
      final CompletionRequest req,
      final CompletionSite site) {
    final var semanticContext = semanticContext(site, req, parsed);
    final boolean recoveredMixedStatement =
        shouldTreatTypeReferenceAsMixedSimpleName(parsed, injected);
    if (semanticContext != null
        && semanticContext.expectedValue() instanceof ExpectedValue.NoSlot
        && !hasUppercasePrefix(injected)) {
      return CompletionOutcome.of(List.of());
    }

    final var javacCandidates = completeJavacSimpleName(parsed, injected, req, semanticContext);
    final var enumCandidates =
        enumEqualityCandidates(parsed, injected.prefix(), semanticContext, req);
    final var enumCaseCandidates =
        enumCaseLabelCandidates(parsed, injected.prefix(), semanticContext);

    if (isStringCaseLabel(parsed, semanticContext)) {
      return CompletionOutcome.of(List.of());
    }

    if (isCaseLabelTypePattern(parsed, enumCaseCandidates, semanticContext)) {
      return completeSimpleNameTypeReferenceWithLang(injected, req, TypeReferenceRole.ORDINARY);
    }

    final var keywordCandidates =
        KeywordProvider.suggestCandidates(parsed, injected.prefix(), injected.context());
    LOG.fine(
        () ->
            "[completion] simple-name candidates javac=%d enum=%d enumCase=%d keywords=%d semantic=%s"
                .formatted(
                    javacCandidates.size(),
                    enumCandidates.size(),
                    enumCaseCandidates.size(),
                    keywordCandidates.size(),
                    semanticContext != null ? semanticContext.expectedValue() : null));
    final var rankingContext =
        recoveredMixedStatement
                || (semanticContext != null
                    && semanticContext.expectedValue() instanceof ExpectedValue.NoSlot)
            ? null
            : semanticContext;

    final List<CompletionCandidate> candidates;
    if (parsed.sentinelContext() == SentinelContext.CASE_LABEL && !enumCaseCandidates.isEmpty()) {
      candidates = enumCaseCandidates;
    } else {
      candidates =
          Stream.of(javacCandidates, enumCandidates, keywordCandidates)
              .flatMap(List::stream)
              .toList();
    }

    final List<CompletionItem> items = presentSimpleNameCandidates(candidates, rankingContext);

    if (!hasUppercasePrefix(injected)) {
      return new CompletionOutcome(items, null);
    }

    // Type-index types: uppercase prefix in statement context only (avoids flooding arg lists)
    final CompletionOutcome withTypes =
        hasUppercasePrefix(injected)
            ? mergeSimpleNameAndTypeIndexItems(
                items,
                completeSimpleNameTypeReferenceWithLang(injected, req, TypeReferenceRole.ORDINARY))
            : new CompletionOutcome(items, null);

    // Static member fit: uppercase prefix + expected type, works in both statement and expression
    if (semanticContext == null
        || recoveredMixedStatement
        || semanticContext.expectedValue() instanceof ExpectedValue.NoSlot) {
      return withTypes;
    }

    final var staticFitCandidates =
        staticMemberFitCandidates(injected.prefix(), semanticContext, req.typeIndex());
    if (staticFitCandidates.isEmpty()) {
      return withTypes;
    }

    final List<RankedCompletionCandidate> ranked =
        CompletionCandidateRanker.rank(staticFitCandidates, semanticContext);
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
      final SentinelInjectionResult injected,
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

  private static List<CompletionCandidate> enumEqualityCandidates(
      final ParsedSentinel parsed,
      final String prefix,
      final SemanticCompletionContext semanticContext,
      final CompletionRequest req) {
    if (!parsed.inEqualityComparison()) {
      return List.of();
    }

    final TypeElement typeEl = expectedEnumType(semanticContext);
    if (typeEl == null) {
      return List.of();
    }

    return new CandidateGenerator(semanticContext.analysis())
        .proposeEnumConstantCandidates(typeEl, prefix, req.cursorOffset());
  }

  private static List<CompletionCandidate> enumCaseLabelCandidates(
      final ParsedSentinel parsed,
      final String prefix,
      final SemanticCompletionContext semanticContext) {
    if (parsed.sentinelContext() != SentinelContext.CASE_LABEL) {
      return List.of();
    }

    final TypeElement typeEl = expectedEnumType(semanticContext);
    if (typeEl == null) {
      return List.of();
    }

    return new CandidateGenerator(semanticContext.analysis())
        .proposeUnqualifiedEnumConstantCandidates(typeEl, prefix);
  }

  private static boolean isCaseLabelTypePattern(
      final ParsedSentinel parsed,
      final List<CompletionCandidate> enumCaseCandidates,
      final SemanticCompletionContext semanticContext) {
    if (parsed.sentinelContext() != SentinelContext.CASE_LABEL) {
      return false;
    }

    if (!enumCaseCandidates.isEmpty()) {
      return false;
    }

    if (semanticContext == null) {
      return false;
    }

    return semanticContext.expectedValue() instanceof ExpectedValue.Type(final TypeMirror t)
        && t.getKind() == TypeKind.DECLARED
        && !isExpectedString(semanticContext);
  }

  private static boolean isStringCaseLabel(
      final ParsedSentinel parsed, final SemanticCompletionContext semanticContext) {
    return parsed.sentinelContext() == SentinelContext.CASE_LABEL
        && isExpectedString(semanticContext);
  }

  private static boolean isExpectedString(final SemanticCompletionContext semanticContext) {
    if (semanticContext == null) {
      return false;
    }

    if (!(semanticContext.expectedValue() instanceof ExpectedValue.Type(final TypeMirror type))) {
      return false;
    }

    final var element = semanticContext.analysis().types().asElement(type);
    return element instanceof final TypeElement typeElement
        && "java.lang.String".equals(typeElement.getQualifiedName().toString());
  }

  private static TypeElement expectedEnumType(final SemanticCompletionContext semanticContext) {
    if (semanticContext == null) {
      return null;
    }

    if (!(semanticContext.expectedValue() instanceof ExpectedValue.Type(final TypeMirror type))) {
      return null;
    }

    final var el = semanticContext.analysis().types().asElement(type);
    if (el instanceof final TypeElement typeEl && typeEl.getKind() == ElementKind.ENUM) {
      return typeEl;
    }

    return null;
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
      final CompletionSite site, final CompletionRequest req, final ParsedSentinel parsed) {
    if (req.cached() == null || req.cached().analysis() == null) {
      return null;
    }

    return SemanticCompletionContext.from(site, req, parsed, req.cached().analysis());
  }

  private static SemanticCompletionContext semanticContext(
      final CompletionSite site,
      final CompletionRequest req,
      final ParsedSentinel parsed,
      final AttributedFileAnalysis analysis) {
    return analysis != null ? SemanticCompletionContext.from(site, req, parsed, analysis) : null;
  }

  private CompletionOutcome completeTypeReference(
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

  private CompletionOutcome completeAnnotationArgument(
      final ParsedSentinel parsed,
      final SentinelInjectionResult injected,
      final CompletionRequest req) {
    final AttributedFileAnalysis analysis = resolveAnalysis(req);
    if (analysis == null) {
      return CompletionOutcome.of(List.of());
    }

    final List<CompletionItem> items =
        AnnotationCompletionProvider.completeArgument(parsed, injected, analysis);
    return new CompletionOutcome(items, req.cached() == null ? analysis : null);
  }

  private CompletionOutcome completeAnnotationArgumentValue(
      final ParsedSentinel parsed,
      final SentinelInjectionResult injected,
      final CompletionRequest req) {
    final AttributedFileAnalysis analysis = resolveAnalysis(req);
    if (analysis == null) {
      return CompletionOutcome.of(List.of());
    }

    final List<CompletionItem> items =
        AnnotationCompletionProvider.completeArgumentValue(parsed, injected, analysis);
    return new CompletionOutcome(items, req.cached() == null ? analysis : null);
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

  private CompletionOutcome completeNestedTypes(
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
          resolvePackageCandidates(
              analysis, req.cursorOffset(), parsed.receiverText(), injected.prefix());
      return CompletionOutcome.of(
          packageCandidates.stream().map(CompletionItemPresenter::present).toList());
    }

    final var candidates =
        new CandidateGenerator(analysis).proposeNestedTypes(outer, injected.prefix());
    final List<CompletionItem> items =
        CompletionCandidateRanker.rank(candidates, blankMemberAccessContext(analysis)).stream()
            .map(CompletionItemPresenter::present)
            .toList();
    return new CompletionOutcome(items, null);
  }

  private static List<CompletionCandidate> resolvePackageCandidates(
      final AttributedFileAnalysis analysis,
      final int cursorOffset,
      final String receiverText,
      final String prefix) {
    final var scope = TypeResolver.resolveScope(analysis, cursorOffset);
    return new ImportCompletionProvider(analysis, scope).proposeCandidates(receiverText, prefix);
  }

  private static List<CompletionCandidate> classLiteralCandidates(final String prefix) {
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

  private static boolean typeLikeReceiver(final String receiverText) {
    final int dot = receiverText.lastIndexOf('.');
    final String simpleName = dot >= 0 ? receiverText.substring(dot + 1) : receiverText;
    return !simpleName.isEmpty() && Character.isUpperCase(simpleName.codePointAt(0));
  }

  private CompletionOutcome completeSimpleNameTypeReference(
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
    final var validator = new TypeIndexValidator(analysis);
    final var scope =
        analysis != null ? TypeResolver.resolveScope(analysis, req.cursorOffset()) : null;
    final List<CompletionCandidate> typeCandidates =
        candidates.stream()
            .sorted(typeCandidateComparator(injected.prefix()))
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

  private CompletionOutcome completeSimpleNameTypeReferenceWithLang(
      final SentinelInjectionResult injected,
      final CompletionRequest req,
      final TypeReferenceRole role) {
    final var typeIndexOutcome = completeSimpleNameTypeReference(injected, req, role);
    final var withLang = mergeLangTypes(injected.prefix(), req, typeIndexOutcome, role);
    return mergeInFileTypes(injected.prefix(), req, withLang, role);
  }

  private CompletionOutcome completeConstructorTypeReference(
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
            semanticContext(site, req, parsed, initialAnalysis),
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
                semanticContext(site, req, parsed, freshAnalysis),
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

    final List<CompletionCandidate> subtypeCandidates =
        instantiableSubtypeCandidates(prefix, expectedEl, analysis, typeIndex);
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
      final WorkspaceTypeIndex typeIndex) {
    if (typeIndex == null) {
      return List.of();
    }

    final var binaryName = analysis.elements().getBinaryName(expectedEl).toString();
    return typeIndex.transitiveSubtypes(binaryName).stream()
        .filter(CompletionEngine::indexKindMaybeInstantiable)
        .filter(entry -> entry.simpleName().startsWith(prefix))
        .sorted(typeCandidateComparator(prefix))
        .limit(TYPE_INDEX_RESULT_LIMIT)
        .filter(entry -> resolvesToInstantiableSubtype(entry, expectedEl, analysis))
        .map(CandidateFactory::typeIndexCandidate)
        .toList();
  }

  private static boolean resolvesToInstantiableSubtype(
      final TypeIndexEntry entry,
      final TypeElement expectedEl,
      final AttributedFileAnalysis analysis) {
    final var subtypeEl = analysis.elements().getTypeElement(entry.qualifiedName());
    return subtypeEl != null && !subtypeEl.equals(expectedEl) && directlyInstantiable(subtypeEl);
  }

  private static boolean indexKindMaybeInstantiable(final TypeIndexEntry entry) {
    return entry.kind() == io.github.aglibs.lathe.core.typeindex.TypeKind.CLASS
        || entry.kind() == io.github.aglibs.lathe.core.typeindex.TypeKind.RECORD;
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

  private static Comparator<TypeIndexEntry> typeCandidateComparator(final String prefix) {
    return Comparator.comparing((TypeIndexEntry e) -> !e.simpleName().startsWith(prefix))
        .thenComparing(e -> !"java.lang".equals(e.packageName()))
        .thenComparingInt(e -> e.qualifiedName().length())
        .thenComparing(TypeIndexEntry::qualifiedName);
  }

  private AttributedFileAnalysis resolveAnalysis(final CompletionRequest req) {
    return req.cached() != null
        ? req.cached().analysis()
        : (compiler != null ? compiler.reattribute(req.uri(), req.content()) : null);
  }

  private static boolean hasUppercasePrefix(final SentinelInjectionResult injected) {
    return !injected.prefix().isEmpty()
        && Character.isUpperCase(injected.prefix().charAt(0))
        && injected.receiverText() == null;
  }

  private static boolean shouldTreatTypeReferenceAsMixedSimpleName(
      final ParsedSentinel parsed, final SentinelInjectionResult injected) {
    return parsed.receiverText() == null
        && parsed.typeReferenceRole() == TypeReferenceRole.ORDINARY
        && injected.context() == SentinelInjector.Context.STATEMENT
        && hasUppercasePrefix(injected);
  }

  private static CompletionOutcome mergeSimpleNameAndTypeIndexItems(
      final List<CompletionItem> simpleNameItems, final CompletionOutcome typeIndexOutcome) {
    return mergeOutcomes(
        simpleNameItems, typeIndexOutcome.items(), null, typeIndexOutcome.incomplete());
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

  private static String completionIdentity(final CompletionItem item) {
    return "%s\u0000%s".formatted(item.getLabel(), item.getDetail());
  }

  private CompletionOutcome completeMemberAccess(
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
      return completeMemberAccessTypeIndexFallback(
          parsed, injected, snapshot, req.cursorOffset(), req.typeIndex());
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
                isStaticAccess ? classLiteralCandidates(injected.prefix()).stream() : Stream.of())
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

  private CompletionOutcome completeMemberAccessTypeIndexFallback(
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
          CompletionCandidateRanker.rank(candidates, blankMemberAccessContext(snapshot)).stream()
              .map(CompletionItemPresenter::present)
              .toList();
      if (!items.isEmpty()) {
        applyTypeImportEdit(items, candidate.qualifiedName(), snapshot);
        return CompletionOutcome.of(items);
      }
    }

    return CompletionOutcome.of(List.of());
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

  private List<CompletionCandidate> staticMemberFitCandidates(
      final String prefix,
      final SemanticCompletionContext context,
      final WorkspaceTypeIndex typeIndex) {
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

    final var result = new ArrayList<CompletionCandidate>();
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

        final var qualifiedMember =
            "%s.%s".formatted(entry.qualifiedName(), member.getSimpleName());
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
    final var qualifiedMember = "%s.%s".formatted(typeQualifiedName, member.getSimpleName());
    return new CompletionCandidate(
        "%s.%s".formatted(typeName, member.getSimpleName()),
        base.label(),
        base.kind(),
        typeName,
        base.insertText(),
        base.snippet(),
        null,
        base.labelDetail(),
        base.labelDescription(),
        base.valueType(),
        typeQualifiedName,
        new ImportEdit(qualifiedMember, true));
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

  private static SemanticCompletionContext blankMemberAccessContext(
      final AttributedFileAnalysis snapshot) {
    return new SemanticCompletionContext(
        snapshot, new ExpectedValue.Unknown(), false, false, false, null);
  }

  /**
   * Returns true when the VARIABLE_DECLARATION sentinel is a real name slot (the user has already
   * typed an explicit type before the cursor). Returns false for javac error-recovery artifacts
   * where the parser invents a synthetic type using the enclosing class name.
   */
  private static boolean isRealNameSlot(final ParsedSentinel parsed) {
    return parsed.declaredTypeText() != null
        && !parsed.declaredTypeText().equals(parsed.enclosingClass());
  }
}
