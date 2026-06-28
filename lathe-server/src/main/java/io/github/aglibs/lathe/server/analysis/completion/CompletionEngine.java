package io.github.aglibs.lathe.server.analysis.completion;

import com.sun.source.tree.ImportTree;
import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.server.analysis.JavaSourceCompiler;
import io.github.aglibs.lathe.server.analysis.SourceParser;
import io.github.aglibs.lathe.server.analysis.WorkspaceTypeIndex;
import java.util.ArrayList;
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
  private static final int STATIC_MEMBER_FIT_LIMIT = 20;
  private static final int MODULE_SEGMENT_LIMIT = 50;

  private final SentinelParser sentinelParser;
  private final JavaSourceCompiler compiler;
  private final MemberAccessCompleter memberAccessCompleter;
  private final TypeReferenceCompleter typeReferenceCompleter;

  public CompletionEngine(final SourceParser parser, final JavaSourceCompiler compiler) {
    this.sentinelParser = new SentinelParser(parser);
    this.compiler = compiler;
    this.memberAccessCompleter = new MemberAccessCompleter(compiler);
    this.typeReferenceCompleter = new TypeReferenceCompleter(compiler);
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
    final var parsed = sentinelParser.parse(injected, req.pos().getLine(), version, req.uri());
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

    if (parsed.sentinelContext() == SentinelContext.MODULE_DIRECTIVE) {
      return completeModuleDirective(
          injected, parsed.directiveKeyword(), req.typeIndex(), req.moduleNames());
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
                  : typeReferenceCompleter.completeConstructorTypeReference(
                      injected, req, parsed, site);
          case TYPE_REFERENCE ->
              shouldTreatTypeReferenceAsMixedSimpleName(parsed, injected)
                  ? completeSimpleName(parsed, injected, req, site)
                  : typeReferenceCompleter.completeTypeReference(parsed, injected, req);
          case ANNOTATION_CONTEXT ->
              typeReferenceCompleter.completeSimpleNameTypeReferenceWithLang(
                  injected, req, parsed.typeReferenceRole());
          case ANNOTATION_ARGUMENT -> completeAnnotationArgument(parsed, injected, req);
          case ANNOTATION_ARGUMENT_VALUE -> completeAnnotationArgumentValue(parsed, injected, req);
          case VARIABLE_DECLARATION ->
              isRealNameSlot(parsed)
                  ? CompletionOutcome.of(List.of())
                  : (parsed.enclosingMethod() == null
                      ? typeReferenceCompleter.completeTypeReference(parsed, injected, req)
                      : CompletionOutcome.of(List.of()));
          case MEMBER_ACCESS, STATIC_IMPORT ->
              memberAccessCompleter.complete(parsed, injected, req, site);
          case LAMBDA_BODY ->
              parsed.receiverText() != null
                  ? memberAccessCompleter.complete(parsed, injected, req, site)
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
      return typeReferenceCompleter.completeSimpleNameTypeReferenceWithLang(
          injected, req, TypeReferenceRole.ORDINARY);
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
            ? TypeReferenceCompleter.mergeSimpleNameAndTypeIndexItems(
                items,
                typeReferenceCompleter.completeSimpleNameTypeReferenceWithLang(
                    injected, req, TypeReferenceRole.ORDINARY))
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
                    TypeReferenceCompleter::completionIdentity,
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

  /**
   * Returns true when the VARIABLE_DECLARATION sentinel is a real name slot (the user has already
   * typed an explicit type before the cursor). Returns false for javac error-recovery artifacts
   * where the parser invents a synthetic type using the enclosing class name.
   */
  private static boolean isRealNameSlot(final ParsedSentinel parsed) {
    return parsed.declaredTypeText() != null
        && !parsed.declaredTypeText().equals(parsed.enclosingClass());
  }

  private static CompletionOutcome completeModuleDirective(
      final SentinelInjectionResult injected,
      final ModuleDirectiveKind kind,
      final WorkspaceTypeIndex typeIndex,
      final List<String> moduleNames) {
    final List<CompletionItem> items =
        switch (kind) {
          case EXPORTS, OPENS, USES, PROVIDES, WITH -> nameSegmentItems(injected, typeIndex);
          case REQUIRES, REQUIRES_TRANSITIVE, REQUIRES_STATIC ->
              moduleNameItems(injected, moduleNames);
          case MODULE -> List.of();
          case NONE -> directiveKeywordItems();
        };
    return CompletionOutcome.of(items);
  }

  private static List<CompletionItem> moduleNameItems(
      final SentinelInjectionResult injected, final List<String> moduleNames) {
    final String prefix =
        injected.receiverText() != null
            ? "%s.%s".formatted(injected.receiverText(), injected.prefix())
            : injected.prefix();
    return moduleNames.stream()
        .filter(name -> name.startsWith(prefix))
        .map(CompletionEngine::toModuleItem)
        .toList();
  }

  private static List<CompletionItem> nameSegmentItems(
      final SentinelInjectionResult injected, final WorkspaceTypeIndex typeIndex) {
    final String dotPrefix =
        injected.receiverText() != null ? "%s.".formatted(injected.receiverText()) : "";
    return typeIndex.searchByBinaryPrefix(dotPrefix, MODULE_SEGMENT_LIMIT).stream()
        .filter(seg -> seg.startsWith(injected.prefix()))
        .map(CompletionEngine::toModuleItem)
        .toList();
  }

  private static CompletionItem toModuleItem(final String label) {
    final var item = new CompletionItem(label);
    item.setKind(CompletionItemKind.Module);
    return item;
  }

  private static List<CompletionItem> directiveKeywordItems() {
    return Stream.of("requires", "exports", "opens", "uses", "provides")
        .map(CompletionEngine::toKeywordItem)
        .toList();
  }

  private static CompletionItem toKeywordItem(final String keyword) {
    final var item = new CompletionItem(keyword);
    item.setKind(CompletionItemKind.Keyword);
    return item;
  }
}
