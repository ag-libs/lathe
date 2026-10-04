package io.github.aglibs.lathe.server.analysis;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;

/** Single-abstract-method (SAM) detection shared by completion and go-to-implementation. */
public final class FunctionalInterfaces {

  private FunctionalInterfaces() {}

  /**
   * The single abstract method of {@code type} when it is a functional interface, else {@code null}
   * — {@code null} when the type has no abstract method, or more than one once the {@code Object}
   * methods a functional interface may redeclare are excluded.
   */
  public static ExecutableElement singleAbstractMethod(
      final TypeElement type, final Elements elements) {
    ExecutableElement result = null;
    for (final Element member : elements.getAllMembers(type)) {
      if (!(member instanceof final ExecutableElement method)
          || !method.getModifiers().contains(Modifier.ABSTRACT)
          || overridesObjectMethod(method, type, elements)) {
        continue;
      }

      if (result != null) {
        return null;
      }

      result = method;
    }

    return result;
  }

  private static boolean overridesObjectMethod(
      final ExecutableElement method, final TypeElement owner, final Elements elements) {
    final var objectType = elements.getTypeElement("java.lang.Object");
    if (objectType == null) {
      return false;
    }

    return elements.getAllMembers(objectType).stream()
        .filter(member -> member.getKind() == ElementKind.METHOD)
        .map(ExecutableElement.class::cast)
        .anyMatch(objectMethod -> elements.overrides(method, objectMethod, owner));
  }
}
