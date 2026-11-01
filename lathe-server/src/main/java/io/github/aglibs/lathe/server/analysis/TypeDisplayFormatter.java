package io.github.aglibs.lathe.server.analysis;

import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Types;

public final class TypeDisplayFormatter {

  private final Types types;
  private final Predicate<TypeElement> qualified;

  public TypeDisplayFormatter(final Types types) {
    this(types, type -> false);
  }

  // Writes the types `qualified` accepts by their qualified name, the rest by their simple one.
  public TypeDisplayFormatter(final Types types, final Predicate<TypeElement> qualified) {
    this.types = types;
    this.qualified = qualified;
  }

  public String format(final TypeMirror type) {
    if (type instanceof final DeclaredType declaredType) {
      return formatDeclaredType(declaredType);
    }

    if (type instanceof final ArrayType arrayType) {
      return "%s[]".formatted(format(arrayType.getComponentType()));
    }

    if (type instanceof final TypeVariable typeVariable) {
      return typeVariable.asElement().getSimpleName().toString();
    }

    if (type instanceof final WildcardType wildcardType) {
      return formatWildcardType(wildcardType);
    }

    return type.toString();
  }

  private String formatDeclaredType(final DeclaredType type) {
    final var typeElement = types.asElement(type);
    final String name =
        typeElement instanceof final TypeElement el
            ? (qualified.test(el) ? el.getQualifiedName() : el.getSimpleName()).toString()
            : type.toString();
    if (type.getTypeArguments().isEmpty()) {
      return name;
    }

    final String args =
        type.getTypeArguments().stream().map(this::format).collect(Collectors.joining(", "));
    return "%s<%s>".formatted(name, args);
  }

  private String formatWildcardType(final WildcardType type) {
    if (type.getExtendsBound() != null) {
      return "? extends %s".formatted(format(type.getExtendsBound()));
    }

    if (type.getSuperBound() != null) {
      return "? super %s".formatted(format(type.getSuperBound()));
    }

    return "?";
  }
}
