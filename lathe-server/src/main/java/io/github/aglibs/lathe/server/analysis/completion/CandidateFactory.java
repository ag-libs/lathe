package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

final class CandidateFactory {

  private static final Logger LOG = Logger.getLogger(CandidateFactory.class.getName());

  private final Types types;
  private final TypeDisplayFormatter typeDisplayFormatter;

  CandidateFactory(final Types types) {
    this.types = types;
    this.typeDisplayFormatter = new TypeDisplayFormatter(types);
  }

  static CompletionCandidate typeIndexCandidate(final TypeIndexEntry entry) {
    final var importEdit =
        "java.lang".equals(entry.packageName())
            ? null
            : new ImportEdit(entry.qualifiedName(), false);
    return typeCandidate(
        entry.simpleName(), entry.qualifiedName(), kindFor(entry.kind()), null, importEdit);
  }

  private static CandidateKind kindFor(final TypeKind typeKind) {
    return switch (typeKind) {
      case INTERFACE -> CandidateKind.TYPE_INTERFACE;
      case ENUM -> CandidateKind.TYPE_ENUM;
      case RECORD, CLASS, ANNOTATION, UNKNOWN -> CandidateKind.TYPE_CLASS;
    };
  }

  static CompletionCandidate typeElementCandidate(final TypeElement el) {
    return typeElementCandidate(el, null);
  }

  static CompletionCandidate typeElementCandidate(
      final TypeElement el, final ImportEdit importEdit) {
    final var simpleName = el.getSimpleName().toString();
    return typeCandidate(
        simpleName,
        el.getQualifiedName().toString(),
        kindForElement(el.getKind()),
        el.asType(),
        importEdit);
  }

  private static CandidateKind kindForElement(final ElementKind kind) {
    return switch (kind) {
      case INTERFACE, ANNOTATION_TYPE -> CandidateKind.TYPE_INTERFACE;
      case ENUM -> CandidateKind.TYPE_ENUM;
      default -> CandidateKind.TYPE_CLASS;
    };
  }

  private static CompletionCandidate typeCandidate(
      final String simpleName,
      final String qualifiedName,
      final CandidateKind kind,
      final TypeMirror valueType,
      final ImportEdit importEdit) {
    final String packageName = QualifiedNames.packageName(qualifiedName).orElse(null);
    return new CompletionCandidate(
        simpleName,
        simpleName,
        kind,
        qualifiedName,
        simpleName,
        false,
        null,
        null,
        packageName,
        valueType,
        qualifiedName,
        importEdit);
  }

  CompletionCandidate variableCandidate(final String name, final TypeMirror type) {
    return new CompletionCandidate(
        name, name, CandidateKind.LOCAL_VARIABLE, null, name, false, null, type, null, null);
  }

  CompletionCandidate memberCandidate(final Element el, final DeclaredType receiverType) {
    final var name = el.getSimpleName().toString();
    return switch (el.getKind()) {
      case METHOD -> methodCandidate((ExecutableElement) el, receiverType, name);
      case FIELD, ENUM_CONSTANT -> fieldCandidate(el, name);
      default -> throw new IllegalArgumentException("Unsupported completion element: " + el);
    };
  }

  private CompletionCandidate methodCandidate(
      final ExecutableElement method, final DeclaredType receiverType, final String name) {
    final ExecutableType executableType = resolveExecutableType(method, receiverType);
    final List<? extends TypeMirror> paramTypes = executableType.getParameterTypes();
    final String paramsWithNames = paramsWithNames(method, paramTypes);
    final boolean snippet = !paramTypes.isEmpty();
    final String returnType = typeDisplayFormatter.format(executableType.getReturnType());
    final String declaringType = declaringTypeSimpleName(method);
    return new CompletionCandidate(
        name,
        name,
        CandidateKind.METHOD,
        "%s.%s(%s) : %s".formatted(declaringType, name, paramsWithNames, returnType),
        snippet ? "%s($1)".formatted(name) : "%s()".formatted(name),
        snippet,
        null,
        "(%s)".formatted(paramsWithNames),
        returnType,
        executableType.getReturnType(),
        declaringType(method),
        null);
  }

  private CompletionCandidate fieldCandidate(final Element field, final String name) {
    return new CompletionCandidate(
        name,
        name,
        CandidateKind.FIELD,
        typeDisplayFormatter.format(field.asType()),
        name,
        false,
        null,
        field.asType(),
        declaringType(field),
        null);
  }

  private static String declaringType(final Element element) {
    return element.getEnclosingElement() instanceof final TypeElement typeElement
        ? typeElement.getQualifiedName().toString()
        : null;
  }

  private static String declaringTypeSimpleName(final Element element) {
    return element.getEnclosingElement() instanceof final TypeElement typeElement
        ? typeElement.getSimpleName().toString()
        : "";
  }

  private String paramsWithNames(
      final ExecutableElement method, final List<? extends TypeMirror> paramTypes) {
    final var parameters = method.getParameters();
    return IntStream.range(0, paramTypes.size())
        .mapToObj(
            index -> {
              final String paramName =
                  index < parameters.size() ? parameters.get(index).getSimpleName().toString() : "";
              final TypeMirror type = paramTypes.get(index);
              final String typeText = typeDisplayFormatter.format(type);
              return displayParameter(typeText, paramName);
            })
        .collect(Collectors.joining(", "));
  }

  private static String displayParameter(final String typeText, final String name) {
    if (name.isBlank() || name.matches("arg\\d+")) {
      return typeText;
    }

    return "%s %s".formatted(typeText, name);
  }

  private ExecutableType resolveExecutableType(
      final ExecutableElement method, final DeclaredType receiverType) {
    try {
      return (ExecutableType) types.asMemberOf(receiverType, method);
    } catch (final IllegalArgumentException e) {
      LOG.log(
          Level.FINE,
          e,
          () ->
              "[completion-item] asMemberOf failed for %s on %s"
                  .formatted(method.getSimpleName(), receiverType));
      return (ExecutableType) method.asType();
    }
  }
}
