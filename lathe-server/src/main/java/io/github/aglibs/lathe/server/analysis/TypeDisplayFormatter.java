package io.github.aglibs.lathe.server.analysis;

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

  public TypeDisplayFormatter(final Types types) {
    this.types = types;
  }

  public String format(final TypeMirror type) {
    return switch (type) {
      case DeclaredType declaredType -> formatDeclaredType(declaredType);
      case ArrayType arrayType -> "%s[]".formatted(format(arrayType.getComponentType()));
      case TypeVariable typeVariable -> typeVariable.asElement().getSimpleName().toString();
      case WildcardType wildcardType -> formatWildcardType(wildcardType);
      default -> type.toString();
    };
  }

  private String formatDeclaredType(final DeclaredType type) {
    final var typeElement = types.asElement(type);
    final String name =
        typeElement instanceof final TypeElement el
            ? el.getSimpleName().toString()
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
