package io.github.aglibs.lathe.analysis.completion;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.Scope;
import com.sun.source.util.TreePathScanner;
import io.github.aglibs.lathe.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.analysis.CompletionOutcome;
import io.github.aglibs.lathe.analysis.JavaSourceCompiler;
import io.github.aglibs.lathe.analysis.WorkspaceTypeIndex;
import io.github.aglibs.lathe.analysis.internal.IndexTypes;
import io.github.aglibs.lathe.analysis.internal.SourceTypeNames;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
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
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;

final class TypeReferenceCompleter {

  private static final Logger LOG = Logger.getLogger(TypeReferenceCompleter.class.getName());
  private static final int TYPE_INDEX_RESULT_LIMIT = 50;
  private static final int TYPE_INDEX_VALIDATION_CANDIDATE_LIMIT = 1_000;
  private static final Set<String> ACCESS_MODIFIER_KEYWORDS =
      Set.of("public", "private", "protected");
  private static final Set<String> OTHER_MODIFIER_KEYWORDS =
      Set.of("static", "final", "abstract", "synchronized", "transient", "volatile");

  private static final Set<TypeReferenceRole> SUPERTYPE_ROLES =
      Set.of(
          TypeReferenceRole.CLASS_EXTENDS,
          TypeReferenceRole.CLASS_IMPLEMENTS,
          TypeReferenceRole.INTERFACE_EXTENDS,
          TypeReferenceRole.RECORD_IMPLEMENTS);
  private static final Set<CompletionItemKind> TYPE_KINDS =
      Set.of(CompletionItemKind.Class, CompletionItemKind.Interface, CompletionItemKind.Enum);

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
    final var withLangTypes =
        parsed.sentinelContext() == SentinelContext.TYPE_REFERENCE
            ? mergeLangTypes(injected.prefix(), req, typeIndexOutcome, parsed.typeReferenceRole())
            : typeIndexOutcome;
    // A type may name a type of this file the index has not seen yet: a new one, or the type being
    // declared (Comparable<Self>).
    final boolean typeArgument =
        parsed.typeReferenceRole() == TypeReferenceRole.TYPE_ARGUMENT
            || parsed.typeReferenceRole() == TypeReferenceRole.ARRAY_TYPE_ARGUMENT;
    final var withInFileTypes =
        typeArgument || parsed.sentinelContext() == SentinelContext.TYPE_REFERENCE
            ? mergeInFileTypes(injected.prefix(), req, withLangTypes, parsed.typeReferenceRole())
            : withLangTypes;
    final var validTypes = withoutInvalidSupertypes(withInFileTypes, parsed, req);
    final List<CompletionItem> typeVariables =
        typeVariableCandidates(parsed, injected.prefix()).stream()
            .map(CompletionItemPresenter::present)
            .toList();
    final var visibleTypes = withoutHiddenTypes(validTypes, parsed);
    final var typeRefOutcome =
        typeVariables.isEmpty()
            ? visibleTypes
            : mergeOutcomes(
                typeVariables,
                visibleTypes.items(),
                validTypes.freshAnalysis(),
                validTypes.incomplete());
    if (parsed.typeReferenceRole() == TypeReferenceRole.ARRAY_TYPE_ARGUMENT) {
      final List<CompletionItem> primitives =
          KeywordProvider.primitiveCandidates(injected.prefix()).stream()
              .map(CompletionItemPresenter::present)
              .toList();
      return mergeSimpleNameAndTypeIndexItems(primitives, typeRefOutcome);
    }

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

  // A type variable hides a type of the same simple name: written there, the name means the
  // variable, so offering the type (with its import) would name something else.
  static CompletionOutcome withoutHiddenTypes(
      final CompletionOutcome outcome, final ParsedSentinel parsed) {
    if (parsed.typeVariables().isEmpty()) {
      return outcome;
    }

    final List<CompletionItem> visible =
        outcome.items().stream()
            .filter(
                item ->
                    !(TYPE_KINDS.contains(item.getKind())
                        && parsed.typeVariables().contains(item.getLabel())))
            .toList();
    return new CompletionOutcome(visible, outcome.freshAnalysis(), outcome.incomplete());
  }

  // An additional bound is an interface (JLS 4.4): the type variables in scope only hide types.
  static List<CompletionCandidate> typeVariableCandidates(
      final ParsedSentinel parsed, final String prefix) {
    if (parsed.typeReferenceRole() == TypeReferenceRole.ADDITIONAL_BOUND) {
      return List.of();
    }

    return parsed.typeVariables().stream()
        .filter(name -> name.startsWith(prefix))
        .map(CandidateFactory::typeParameterCandidate)
        .toList();
  }

  // javac rejects a type as a supertype of itself, of a type nested in it or of a subtype of it
  // (cyclic inheritance), and a sealed type that does not permit the declaring type. Compared by
  // qualified name, so a type of the same simple name elsewhere stays. A declaring type the last
  // compile does not hold is new, so neither the file's types nor the index can hold it; before the
  // file's first compile, only its name is known.
  private static CompletionOutcome withoutInvalidSupertypes(
      final CompletionOutcome outcome, final ParsedSentinel parsed, final CompletionRequest req) {
    if (parsed.enclosingClass() == null || !SUPERTYPE_ROLES.contains(parsed.typeReferenceRole())) {
      return outcome;
    }

    final var analysis = req.cached() != null ? req.cached().analysis() : null;
    final TypeElement declaring =
        analysis != null ? declaringTypeAt(analysis, req.cursorOffset(), parsed) : null;
    if (analysis != null && declaring == null) {
      return outcome;
    }

    final List<CompletionItem> items =
        outcome.items().stream()
            .filter(
                item ->
                    !TYPE_KINDS.contains(item.getKind())
                        || (declaring != null
                            ? validSupertype(item.getDetail(), declaring, analysis)
                            : !item.getLabel().equals(parsed.enclosingClass())))
            .toList();
    return new CompletionOutcome(items, outcome.freshAnalysis(), outcome.incomplete());
  }

  private static boolean validSupertype(
      final String qualifiedName,
      final TypeElement declaring,
      final AttributedFileAnalysis analysis) {
    if (qualifiedName == null) {
      return true;
    }

    final var declaringName = declaring.getQualifiedName().toString();
    if (qualifiedName.equals(declaringName) || qualifiedName.startsWith(declaringName + ".")) {
      return false;
    }

    final var supertype = analysis.elements().getTypeElement(qualifiedName);
    if (supertype == null) {
      return true;
    }

    return !withinSubtype(supertype, declaring, analysis)
        && permits(supertype, declaring, analysis);
  }

  // A subtype of the declaring type, or a type nested in one, would close an inheritance cycle.
  private static boolean withinSubtype(
      final TypeElement candidate,
      final TypeElement declaring,
      final AttributedFileAnalysis analysis) {
    final var types = analysis.types();
    final var declaringType = types.erasure(declaring.asType());
    for (Element element = candidate;
        element instanceof final TypeElement type;
        element = element.getEnclosingElement()) {
      if (types.isSubtype(types.erasure(type.asType()), declaringType)) {
        return true;
      }
    }

    return false;
  }

  // A sealed type with no `permits` clause permits the types of its own file; a type with a tree
  // in this analysis is one of this file's.
  private static boolean permits(
      final TypeElement supertype,
      final TypeElement declaring,
      final AttributedFileAnalysis analysis) {
    if (!supertype.getModifiers().contains(Modifier.SEALED)) {
      return true;
    }

    // javac forbids a local or anonymous class from extending a sealed type.
    if (declaring.getNestingKind() == NestingKind.LOCAL
        || declaring.getNestingKind() == NestingKind.ANONYMOUS) {
      return false;
    }

    final var tree = analysis.trees().getTree(supertype);
    if (tree != null && tree.getPermitsClause().isEmpty()) {
      return true;
    }

    for (final TypeMirror permitted : supertype.getPermittedSubclasses()) {
      if (analysis.types().asElement(permitted) instanceof final TypeElement permittedType
          && permittedType.getQualifiedName().contentEquals(declaring.getQualifiedName())) {
        return true;
      }
    }

    return false;
  }

  // The analysis is the file's last compile, which may predate the text at the cursor: a nested
  // type just typed is not in it, and the class around it would be taken for the declaring one.
  // So the class found must carry the name the current parse gives, or it is not trusted.
  private static TypeElement declaringTypeAt(
      final AttributedFileAnalysis analysis, final int offset, final ParsedSentinel parsed) {
    if (analysis.tree() == null) {
      return null;
    }

    final var positions = analysis.trees().getSourcePositions();
    final var found = new ArrayList<TypeElement>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitClass(final ClassTree node, final Void unused) {
        final long start = positions.getStartPosition(analysis.tree(), node);
        final long end = positions.getEndPosition(analysis.tree(), node);
        if (start > offset || end < offset) {
          return null;
        }

        if (analysis.trees().getElement(getCurrentPath()) instanceof final TypeElement type) {
          found.add(type);
        }

        return super.visitClass(node, unused);
      }
    }.scan(analysis.tree(), null);
    if (found.isEmpty()) {
      return null;
    }

    final TypeElement innermost = found.get(found.size() - 1);
    return innermost.getSimpleName().contentEquals(parsed.enclosingClass()) ? innermost : null;
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
      return base;
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
    if (req.typeIndex() == null) {
      return CompletionOutcome.of(List.of());
    }

    // With no prefix the index is left out, so the list misses its types: a client must ask again
    // as the user types rather than narrow this one.
    if (injected.prefix().isEmpty()) {
      return CompletionOutcome.incomplete(List.of());
    }

    // The index's own kind narrows to what fits the position before the validation limit, so a
    // short prefix still reaches an annotation (or interface) behind a thousand classes.
    final List<TypeIndexEntry> candidates =
        req.typeIndex().search(injected.prefix(), Integer.MAX_VALUE).stream()
            .filter(entry -> indexKindAllows(entry.kind(), role))
            .limit(TYPE_INDEX_VALIDATION_CANDIDATE_LIMIT)
            .toList();
    final var analysis = req.cached() != null ? req.cached().analysis() : null;
    LOG.fine(
        () ->
            "[type-index] typeRef prefix=|%s| candidates=%d cached=%s"
                .formatted(injected.prefix(), candidates.size(), analysis != null));
    final var scope =
        analysis != null ? ReceiverResolver.resolveScope(analysis, req.cursorOffset()) : null;
    final SourceTypeNames names =
        analysis != null ? ReceiverResolver.typeNames(analysis, scope) : null;
    final List<CompletionCandidate> typeCandidates =
        candidates.stream()
            .sorted(typeCandidateComparator(injected.prefix(), req.typeIndex()))
            .filter(entry -> IndexTypes.usable(entry, analysis, scope, req.typeIndex()))
            .filter(entry -> typeIndexRoleAllows(entry, analysis, scope, role))
            .limit(TYPE_INDEX_RESULT_LIMIT)
            .map(entry -> CandidateFactory.typeIndexCandidate(entry, analysis, names))
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
        new CandidateGenerator(analysis)
            .proposeNestedTypes(
                outer,
                injected.prefix(),
                ReceiverResolver.resolveScope(analysis, req.cursorOffset()));
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

    final var scope = ReceiverResolver.resolveScope(analysis, cursorOffset);
    return pkg.getEnclosedElements().stream()
        .filter(
            el ->
                el.getKind() == ElementKind.CLASS
                    || el.getKind() == ElementKind.INTERFACE
                    || el.getKind() == ElementKind.ENUM
                    || el.getKind() == ElementKind.ANNOTATION_TYPE)
        .filter(el -> el.getSimpleName().toString().startsWith(prefix))
        .filter(el -> isUsableLangType((TypeElement) el, analysis, scope))
        .filter(el -> typeReferenceRoleAllows((TypeElement) el, analysis, scope, role))
        .map(el -> CandidateFactory.typeElementCandidate((TypeElement) el))
        .toList();
  }

  // java.lang holds package-private types too (AbstractStringBuilder, NamedPackage): javac says
  // which the cursor can use, so code in java.lang itself still gets them; without a scope, only
  // the public ones.
  private static boolean isUsableLangType(
      final TypeElement type, final AttributedFileAnalysis analysis, final Scope scope) {
    return scope != null && scope.getEnclosingClass() != null
        ? analysis.trees().isAccessible(scope, type)
        : type.getModifiers().contains(Modifier.PUBLIC);
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

    final var scope = ReceiverResolver.resolveScope(analysis, cursorOffset);
    final SourceTypeNames names = ReceiverResolver.typeNames(analysis, scope);
    final var result = new ArrayList<CompletionCandidate>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitClass(final ClassTree node, final Void unused) {
        final var el = analysis.trees().getElement(getCurrentPath());
        // An anonymous class has an empty name, which no candidate can carry.
        if (el instanceof final TypeElement te
            && !te.getSimpleName().isEmpty()
            && te.getSimpleName().toString().startsWith(prefix)
            && (te.getNestingKind() != NestingKind.LOCAL || isLocalInScope(te, scope))
            && (scope == null
                || scope.getEnclosingClass() == null
                || analysis.trees().isAccessible(scope, te))
            && typeReferenceRoleAllows(te, analysis, scope, role)) {
          result.add(CandidateFactory.typeElementCandidate(te, names));
        }

        return super.visitClass(node, unused);
      }
    }.scan(analysis.tree(), null);
    return List.copyOf(result);
  }

  // A local class is visible only in its own block, after its declaration.
  private static boolean isLocalInScope(final TypeElement local, final Scope scope) {
    for (Scope current = scope; current != null; current = current.getEnclosingScope()) {
      for (final Element element : current.getLocalElements()) {
        if (element.equals(local)) {
          return true;
        }
      }
    }

    return false;
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
            : ExpectedValueResolver.resolveExpectedArgumentValue(cursorOffset, analysis);
    if (!(expected instanceof final ExpectedValue.Type expectedType)) {
      return List.of();
    }

    final TypeMirror type = expectedType.type();

    if (!(analysis.types().asElement(type) instanceof final TypeElement expectedEl)) {
      return List.of();
    }

    final var scope = ReceiverResolver.resolveScope(analysis, cursorOffset);
    final SourceTypeNames names = ReceiverResolver.typeNames(analysis, scope);
    final List<CompletionCandidate> subtypeCandidates =
        instantiableSubtypeCandidates(prefix, expectedEl, analysis, typeIndex, scope, names);
    if (!(expectedEl.getSimpleName().toString().startsWith(prefix)
        && constructibleType(expectedEl, analysis, null))) {
      return subtypeCandidates;
    }

    return Stream.concat(
            Stream.of(CandidateFactory.typeElementCandidate(expectedEl, names)),
            subtypeCandidates.stream())
        .toList();
  }

  private static List<CompletionCandidate> instantiableSubtypeCandidates(
      final String prefix,
      final TypeElement expectedEl,
      final AttributedFileAnalysis analysis,
      final WorkspaceTypeIndex typeIndex,
      final Scope scope,
      final SourceTypeNames names) {
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
        .map(entry -> CandidateFactory.typeIndexCandidate(entry, analysis, names))
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
        && Instantiation.directlyInstantiable(subtypeEl)
        && (scope == null || analysis.trees().isAccessible(scope, subtypeEl))
        && !Instantiation.accessibleConstructors((DeclaredType) subtypeEl.asType(), analysis, scope)
            .isEmpty();
  }

  private static boolean indexKindMaybeInstantiable(final TypeIndexEntry entry) {
    return entry.kind() == TypeKind.CLASS || entry.kind() == TypeKind.RECORD;
  }

  private static boolean indexKindAllows(final TypeKind kind, final TypeReferenceRole role) {
    if (kind == TypeKind.UNKNOWN) {
      return true;
    }

    return switch (role) {
      case ORDINARY, TYPE_ARGUMENT, ARRAY_TYPE_ARGUMENT -> true;
      case CONSTRUCTOR ->
          kind == TypeKind.CLASS || kind == TypeKind.RECORD || kind == TypeKind.INTERFACE;
      case CLASS_EXTENDS, THROWS -> kind == TypeKind.CLASS;
      case CLASS_IMPLEMENTS, INTERFACE_EXTENDS, RECORD_IMPLEMENTS, ADDITIONAL_BOUND ->
          kind == TypeKind.INTERFACE;
      case ANNOTATION -> kind == TypeKind.ANNOTATION;
    };
  }

  private static boolean typeIndexRoleAllows(
      final TypeIndexEntry entry,
      final AttributedFileAnalysis analysis,
      final Scope scope,
      final TypeReferenceRole role) {
    if (analysis == null
        || role == TypeReferenceRole.ORDINARY
        || role == TypeReferenceRole.TYPE_ARGUMENT
        || role == TypeReferenceRole.ARRAY_TYPE_ARGUMENT) {
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
      case ORDINARY, TYPE_ARGUMENT, ARRAY_TYPE_ARGUMENT -> true;
      case CONSTRUCTOR -> constructibleType(typeEl, analysis, scope);
      case CLASS_EXTENDS -> extendableClass(typeEl);
      case CLASS_IMPLEMENTS, INTERFACE_EXTENDS, RECORD_IMPLEMENTS, ADDITIONAL_BOUND ->
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

    return !Instantiation.accessibleConstructors((DeclaredType) typeEl.asType(), analysis, scope)
        .isEmpty();
  }

  private static boolean extendableClass(final TypeElement typeEl) {
    return typeEl.getKind() == ElementKind.CLASS && !typeEl.getModifiers().contains(Modifier.FINAL);
  }

  private static boolean throwableType(
      final TypeElement typeEl, final AttributedFileAnalysis analysis) {
    final var throwable = analysis.elements().getTypeElement("java.lang.Throwable");
    return throwable != null && analysis.types().isAssignable(typeEl.asType(), throwable.asType());
  }

  // A name the prefix starts (case-sensitively) leads; the index ranks the rest.
  private static Comparator<TypeIndexEntry> typeCandidateComparator(
      final String prefix, final WorkspaceTypeIndex typeIndex) {
    return Comparator.comparing((TypeIndexEntry e) -> !e.simpleName().startsWith(prefix))
        .thenComparing(typeIndex.ranking());
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
