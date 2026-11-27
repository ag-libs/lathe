package io.github.aglibs.lathe.analysis.completion;

import io.github.aglibs.lathe.analysis.AttributedFileAnalysis;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import org.eclipse.lsp4j.CompletionItem;

final class AnnotationCompletionProvider {

  private static final Set<CandidateKind> NESTED_TYPE_KINDS =
      Set.of(CandidateKind.TYPE_CLASS, CandidateKind.TYPE_INTERFACE, CandidateKind.TYPE_ENUM);

  private AnnotationCompletionProvider() {}

  static List<CompletionItem> completeArgument(
      final ParsedSentinel parsed,
      final SentinelInjectionResult injected,
      final AttributedFileAnalysis analysis) {
    final TypeElement annotationType = resolveAnnotationType(parsed.annotationTypeText(), analysis);
    if (annotationType == null) {
      return List.of();
    }

    return annotationType.getEnclosedElements().stream()
        .filter(el -> el.getKind() == ElementKind.METHOD)
        .map(ExecutableElement.class::cast)
        .filter(el -> el.getParameters().isEmpty())
        .filter(el -> el.getSimpleName().toString().startsWith(injected.prefix()))
        .map(AnnotationCompletionProvider::annotationElementCandidate)
        .map(CompletionItemPresenter::present)
        .toList();
  }

  static List<CompletionItem> completeArgumentValue(
      final ParsedSentinel parsed,
      final SentinelInjectionResult injected,
      final AttributedFileAnalysis analysis,
      final int cursorOffset) {
    final TypeMirror elementType =
        resolveAnnotationElementType(parsed.annotationTypeText(), elementName(parsed), analysis);
    if (elementType == null) {
      return completeArgument(parsed, injected, analysis);
    }

    final List<CompletionItem> values =
        valueCandidates(parsed, injected, analysis, cursorOffset, elementType);
    // A bare name is the `value()` shorthand or the start of a named element (CQ-0081): the names
    // first, then the values, each group in its own order.
    if (!bareArgument(parsed)) {
      return values;
    }

    final List<CompletionItem> names = completeArgument(parsed, injected, analysis);
    names.forEach(item -> item.setSortText("0%s".formatted(item.getLabel())));
    values.forEach(
        item ->
            item.setSortText(
                "1%s"
                    .formatted(item.getSortText() != null ? item.getSortText() : item.getLabel())));
    return Stream.concat(names.stream(), values.stream()).toList();
  }

  private static List<CompletionItem> valueCandidates(
      final ParsedSentinel parsed,
      final SentinelInjectionResult injected,
      final AttributedFileAnalysis analysis,
      final int cursorOffset,
      final TypeMirror elementType) {
    final TypeMirror expectedType = annotationValueCompletionType(elementType);
    final var semanticContext =
        new SemanticCompletionContext(
            analysis, new ExpectedValue.Type(expectedType), true, false, false, null);
    final List<CompletionCandidate> candidates =
        Stream.concat(
                KeywordProvider.suggestCandidates(parsed, injected.prefix(), injected.context())
                    .stream(),
                enumConstantCandidates(expectedType, injected.prefix(), analysis, cursorOffset)
                    .stream())
            .toList();
    final List<RankedCompletionCandidate> ranked =
        CompletionCandidateRanker.rank(candidates, semanticContext);
    final List<CompletionItem> items =
        ranked.stream().map(CompletionItemPresenter::present).toList();
    CompletionItemPresenter.applyImportEdits(
        ranked.stream().map(RankedCompletionCandidate::candidate).toList(), items, analysis);
    return items;
  }

  // The element a value is for; a bare argument's is `value()`, the one its shorthand sets.
  private static String elementName(final ParsedSentinel parsed) {
    return bareArgument(parsed) ? SentinelParser.VALUE_ELEMENT : parsed.enclosingMethodName();
  }

  private static boolean bareArgument(final ParsedSentinel parsed) {
    return parsed.enclosingMethodName() == null;
  }

  static boolean isClassValuedElement(
      final ParsedSentinel parsed, final AttributedFileAnalysis analysis) {
    return isClassType(valueType(parsed, analysis));
  }

  // After `Type.` in an element's value, only what JLS 9.7.1 allows there (CQ-0083): an enum
  // element takes one of its constants, a Class element a class literal, any other element a
  // constant field. A nested type may continue the name, and no method call stands there; an
  // element whose type is not known keeps all but the methods.
  static List<CompletionCandidate> qualifiedValueCandidates(
      final List<CompletionCandidate> candidates,
      final ParsedSentinel parsed,
      final AttributedFileAnalysis analysis) {
    final TypeMirror valueType = valueType(parsed, analysis);
    return candidates.stream()
        .filter(candidate -> qualifiedValue(candidate, valueType, analysis))
        .toList();
  }

  private static boolean qualifiedValue(
      final CompletionCandidate candidate,
      final TypeMirror valueType,
      final AttributedFileAnalysis analysis) {
    if (candidate.kind() == CandidateKind.METHOD) {
      return false;
    }

    if (NESTED_TYPE_KINDS.contains(candidate.kind()) || valueType == null) {
      return true;
    }

    if (isClassType(valueType)) {
      return candidate.kind() == CandidateKind.KEYWORD;
    }

    if (valueType instanceof final DeclaredType declaredType
        && declaredType.asElement() instanceof final TypeElement enumType
        && enumType.getKind() == ElementKind.ENUM) {
      return isConstantOf(candidate, enumType, analysis);
    }

    return candidate.kind() == CandidateKind.FIELD;
  }

  private static boolean isConstantOf(
      final CompletionCandidate candidate,
      final TypeElement enumType,
      final AttributedFileAnalysis analysis) {
    final boolean sameType =
        analysis.types() == null
            || candidate.valueType() != null
                && analysis.types().isSameType(candidate.valueType(), enumType.asType());
    return candidate.kind() == CandidateKind.FIELD
        && sameType
        && enumType.getEnclosedElements().stream()
            .anyMatch(
                el ->
                    el.getKind() == ElementKind.ENUM_CONSTANT
                        && el.getSimpleName().contentEquals(candidate.name()));
  }

  // The type a value of the element takes: an array element's component, so `{ … }` holds them.
  private static TypeMirror valueType(
      final ParsedSentinel parsed, final AttributedFileAnalysis analysis) {
    final TypeMirror elementType =
        resolveAnnotationElementType(parsed.annotationTypeText(), elementName(parsed), analysis);
    return elementType == null ? null : annotationValueCompletionType(elementType);
  }

  private static boolean isClassType(final TypeMirror type) {
    return type instanceof final DeclaredType declaredType
        && declaredType.asElement() instanceof final TypeElement typeElement
        && typeElement.getQualifiedName().contentEquals("java.lang.Class");
  }

  private static TypeMirror annotationValueCompletionType(final TypeMirror elementType) {
    return elementType instanceof final ArrayType arrayType
        ? arrayType.getComponentType()
        : elementType;
  }

  private static List<CompletionCandidate> enumConstantCandidates(
      final TypeMirror expectedType,
      final String prefix,
      final AttributedFileAnalysis analysis,
      final int cursorOffset) {
    if (!(expectedType instanceof final DeclaredType declaredType)
        || !(declaredType.asElement() instanceof final TypeElement enumType)
        || enumType.getKind() != ElementKind.ENUM) {
      return List.of();
    }

    return new CandidateGenerator(analysis)
        .proposeEnumConstantCandidates(enumType, prefix, cursorOffset);
  }

  private static TypeMirror resolveAnnotationElementType(
      final String annotationType,
      final String elementName,
      final AttributedFileAnalysis analysis) {
    if (elementName == null) {
      return null;
    }

    final TypeElement typeEl = resolveAnnotationType(annotationType, analysis);
    if (typeEl == null) {
      return null;
    }

    return typeEl.getEnclosedElements().stream()
        .filter(el -> el.getKind() == ElementKind.METHOD)
        .map(ExecutableElement.class::cast)
        .filter(el -> el.getParameters().isEmpty())
        .filter(el -> el.getSimpleName().toString().equals(elementName))
        .findFirst()
        .map(ExecutableElement::getReturnType)
        .orElse(null);
  }

  private static CompletionCandidate annotationElementCandidate(final ExecutableElement element) {
    final var name = element.getSimpleName().toString();
    return new CompletionCandidate(
        name,
        name,
        CandidateKind.PROPERTY,
        element.getReturnType().toString(),
        "%s = ".formatted(name),
        false,
        null,
        element.getReturnType(),
        CandidateFactory.declaringType(element),
        null);
  }

  private static TypeElement resolveAnnotationType(
      final String typeText, final AttributedFileAnalysis analysis) {
    if (typeText == null || typeText.isBlank()) {
      return null;
    }

    if (typeText.indexOf('.') >= 0) {
      return analysis.elements().getTypeElement(typeText);
    }

    final TypeElement samePackage = samePackageType(typeText, analysis);
    if (samePackage != null) {
      return samePackage;
    }

    final TypeElement imported = importedType(typeText, analysis);
    if (imported != null) {
      return imported;
    }

    return analysis.elements().getTypeElement("java.lang." + typeText);
  }

  private static TypeElement samePackageType(
      final String simpleName, final AttributedFileAnalysis analysis) {
    if (analysis.tree() == null || analysis.tree().getPackageName() == null) {
      return null;
    }

    return analysis
        .elements()
        .getTypeElement("%s.%s".formatted(analysis.tree().getPackageName(), simpleName));
  }

  private static TypeElement importedType(
      final String simpleName, final AttributedFileAnalysis analysis) {
    if (analysis.tree() == null) {
      return null;
    }

    for (final var imp : analysis.tree().getImports()) {
      if (imp.isStatic()) {
        continue;
      }

      final var importedName = imp.getQualifiedIdentifier().toString();
      if (importedName.endsWith("." + simpleName)) {
        return analysis.elements().getTypeElement(importedName);
      }

      if (importedName.endsWith(".*")) {
        final TypeElement type =
            analysis
                .elements()
                .getTypeElement(
                    "%s.%s"
                        .formatted(
                            importedName.substring(0, importedName.length() - 2), simpleName));
        if (type != null) {
          return type;
        }
      }
    }

    return null;
  }
}
