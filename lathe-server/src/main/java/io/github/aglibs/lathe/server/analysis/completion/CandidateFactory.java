package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

final class CandidateFactory {

  private static final Logger LOG = Logger.getLogger(CandidateFactory.class.getName());

  private final Types types;

  CandidateFactory(final Types types) {
    this.types = types;
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
    return new CompletionCandidate(
        simpleName,
        simpleName,
        kind,
        qualifiedName,
        simpleName,
        false,
        null,
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
    final List<? extends TypeMirror> paramTypes = resolveParamTypes(method, receiverType);
    final var params =
        paramTypes.stream().map(this::simpleTypeName).collect(Collectors.joining(", "));
    final boolean snippet = !paramTypes.isEmpty();
    return new CompletionCandidate(
        name,
        "%s(%s)".formatted(name, params),
        CandidateKind.METHOD,
        simpleTypeName(method.getReturnType()),
        snippet ? "%s($1)".formatted(name) : "%s()".formatted(name),
        snippet,
        null,
        method.getReturnType(),
        declaringType(method),
        null);
  }

  private CompletionCandidate fieldCandidate(final Element field, final String name) {
    return new CompletionCandidate(
        name,
        name,
        CandidateKind.FIELD,
        simpleTypeName(field.asType()),
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

  private List<? extends TypeMirror> resolveParamTypes(
      final ExecutableElement method, final DeclaredType receiverType) {
    try {
      return ((ExecutableType) types.asMemberOf(receiverType, method)).getParameterTypes();
    } catch (final IllegalArgumentException e) {
      LOG.log(
          Level.FINE,
          e,
          () ->
              "[completion-item] asMemberOf failed for %s on %s"
                  .formatted(method.getSimpleName(), receiverType));
      return method.getParameters().stream().map(VariableElement::asType).toList();
    }
  }

  private String simpleTypeName(final TypeMirror type) {
    final var el = types.asElement(type);
    return el != null ? el.getSimpleName().toString() : type.toString();
  }
}
