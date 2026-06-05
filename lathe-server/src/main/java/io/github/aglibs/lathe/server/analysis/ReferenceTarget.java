package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

public record ReferenceTarget(
    ElementKind kind, String qualifiedName, String simpleName, String erasedDescriptor) {

  public ReferenceTarget {
    ValidCheck.check()
        .notNull(kind, "kind")
        .notBlank(qualifiedName, "qualifiedName")
        .notBlank(simpleName, "simpleName")
        .validate();
  }

  static ReferenceTarget from(final Element element, final Types types, final Elements elements) {
    final var kind = element.getKind();
    final var simpleName = element.getSimpleName().toString();

    return switch (kind) {
      case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE -> {
        final var te = (TypeElement) element;
        yield new ReferenceTarget(kind, te.getQualifiedName().toString(), simpleName, null);
      }
      case METHOD, CONSTRUCTOR -> {
        final var ee = (ExecutableElement) element;
        final var owner = (TypeElement) ee.getEnclosingElement();
        yield new ReferenceTarget(
            kind, elements.getBinaryName(owner).toString(), simpleName, buildDescriptor(ee, types));
      }
      case FIELD, ENUM_CONSTANT -> {
        final var owner = (TypeElement) element.getEnclosingElement();
        yield new ReferenceTarget(kind, elements.getBinaryName(owner).toString(), simpleName, null);
      }
      default ->
          new ReferenceTarget(kind, enclosingBinaryName(element, elements), simpleName, null);
    };
  }

  boolean matches(final Element element, final Types types, final Elements elements) {
    if (element == null || element.getKind() != kind) {
      return false;
    }

    if (!simpleName.equals(element.getSimpleName().toString())) {
      return false;
    }

    return switch (kind) {
      case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE -> {
        final var te = (TypeElement) element;
        yield qualifiedName.equals(te.getQualifiedName().toString());
      }

      case METHOD, CONSTRUCTOR -> {
        final var ee = (ExecutableElement) element;
        final var owner = (TypeElement) ee.getEnclosingElement();
        yield qualifiedName.equals(elements.getBinaryName(owner).toString())
            && erasedDescriptor.equals(buildDescriptor(ee, types));
      }

      case FIELD, ENUM_CONSTANT -> {
        final var owner = (TypeElement) element.getEnclosingElement();
        yield qualifiedName.equals(elements.getBinaryName(owner).toString());
      }
      default -> qualifiedName.equals(enclosingBinaryName(element, elements));
    };
  }

  private static String buildDescriptor(final ExecutableElement method, final Types types) {
    return method.getParameters().stream()
        .map(p -> types.erasure(p.asType()).toString())
        .collect(Collectors.joining(",", "(", ")"));
  }

  private static String enclosingBinaryName(final Element element, final Elements elements) {
    var e = element.getEnclosingElement();
    while (e != null && !(e instanceof TypeElement)) {
      e = e.getEnclosingElement();
    }

    if (e instanceof final TypeElement te) {
      return elements.getBinaryName(te).toString();
    }

    return element.getSimpleName().toString();
  }
}
