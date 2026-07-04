package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import java.util.List;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import org.eclipse.lsp4j.CompletionItem;

final class AnnotationCompletionProvider {

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
      final AttributedFileAnalysis analysis) {
    final TypeMirror elementType =
        resolveAnnotationElementType(
            parsed.annotationTypeText(), parsed.enclosingMethodName(), analysis);
    if (elementType == null) {
      return completeArgument(parsed, injected, analysis);
    }

    final TypeMirror expectedType = annotationValueCompletionType(elementType);
    final var semanticContext =
        new SemanticCompletionContext(
            analysis, new ExpectedValue.Type(expectedType), true, false, false, null);
    final List<CompletionCandidate> candidates =
        Stream.concat(
                KeywordProvider.suggestCandidates(parsed, injected.prefix(), injected.context())
                    .stream(),
                annotationEnumConstantCandidates(expectedType, injected.prefix()).stream())
            .toList();
    return CompletionCandidateRanker.rank(candidates, semanticContext).stream()
        .map(CompletionItemPresenter::present)
        .toList();
  }

  static boolean isClassValuedElement(
      final ParsedSentinel parsed, final AttributedFileAnalysis analysis) {
    final TypeMirror elementType =
        resolveAnnotationElementType(
            parsed.annotationTypeText(), parsed.enclosingMethodName(), analysis);
    if (elementType == null) {
      return false;
    }

    return annotationValueCompletionType(elementType) instanceof final DeclaredType declaredType
        && declaredType.asElement() instanceof final TypeElement typeElement
        && typeElement.getQualifiedName().contentEquals("java.lang.Class");
  }

  private static TypeMirror annotationValueCompletionType(final TypeMirror elementType) {
    return elementType instanceof final ArrayType arrayType
        ? arrayType.getComponentType()
        : elementType;
  }

  private static List<CompletionCandidate> annotationEnumConstantCandidates(
      final TypeMirror expectedType, final String prefix) {
    if (!(expectedType instanceof final DeclaredType declaredType)) {
      return List.of();
    }

    final var typeElement = declaredType.asElement();
    if (!(typeElement instanceof final TypeElement enumType)
        || enumType.getKind() != ElementKind.ENUM) {
      return List.of();
    }

    return enumType.getEnclosedElements().stream()
        .filter(el -> el.getKind() == ElementKind.ENUM_CONSTANT)
        .filter(el -> el.getSimpleName().toString().startsWith(prefix))
        .map(el -> annotationEnumConstantCandidate(enumType, el))
        .toList();
  }

  private static CompletionCandidate annotationEnumConstantCandidate(
      final TypeElement enumType, final Element constant) {
    final var name = constant.getSimpleName().toString();
    return new CompletionCandidate(
        name,
        name,
        CandidateKind.FIELD,
        enumType.getSimpleName().toString(),
        name,
        false,
        null,
        enumType.asType(),
        enumType.getQualifiedName().toString(),
        null);
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
