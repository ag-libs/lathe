package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.analysis.ReferenceTarget;
import java.util.Optional;
import javax.lang.model.element.ElementKind;

// The JavaBeans property an instance field or accessor names, as a template, a framework that reads
// accessors by name (Spring, Jackson, JPA) or a test's hasProperty binds it: a field by its own
// name, an accessor by what follows get, is or set, decapitalised as java.beans.Introspector does.
// The accessor is told by its name and parameter count only: the target carries no return type, so
// an is-method that returns no boolean counts too. A static member is no property.
final class BeanProperties {

  private static final String NO_PARAMETERS = "()";

  private BeanProperties() {}

  static Optional<String> name(final ReferenceTarget target) {
    if (target.staticMember()) {
      return Optional.empty();
    }

    if (target.kind() == ElementKind.FIELD) {
      return Optional.of(target.simpleName());
    }

    if (target.kind() != ElementKind.METHOD || target.erasedDescriptor() == null) {
      return Optional.empty();
    }

    return accessorProperty(target.simpleName(), target.erasedDescriptor());
  }

  private static Optional<String> accessorProperty(final String method, final String descriptor) {
    final boolean noParameters = NO_PARAMETERS.equals(descriptor);
    final boolean oneParameter = !noParameters && !descriptor.contains(",");
    if (method.startsWith("get") && noParameters || method.startsWith("set") && oneParameter) {
      return decapitalized(method.substring(3));
    }

    return method.startsWith("is") && noParameters
        ? decapitalized(method.substring(2))
        : Optional.empty();
  }

  // URL stays URL, City becomes city; a lower-case start (getter, issue) names no property.
  private static Optional<String> decapitalized(final String rest) {
    if (rest.isEmpty() || !Character.isUpperCase(rest.charAt(0))) {
      return Optional.empty();
    }

    if (rest.length() > 1 && Character.isUpperCase(rest.charAt(1))) {
      return Optional.of(rest);
    }

    return Optional.of(Character.toLowerCase(rest.charAt(0)) + rest.substring(1));
  }
}
