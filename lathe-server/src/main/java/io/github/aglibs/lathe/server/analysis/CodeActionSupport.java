package io.github.aglibs.lathe.server.analysis;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

final class CodeActionSupport {

  private CodeActionSupport() {}

  static String typeSimpleName(final TypeMirror type) {
    if (type instanceof DeclaredType dt) {
      return ((TypeElement) dt.asElement()).getSimpleName().toString();
    }
    if (type instanceof ArrayType at) {
      final String component = typeSimpleName(at.getComponentType());
      return component != null ? component + "[]" : null;
    }
    return switch (type.getKind()) {
      case BOOLEAN, BYTE, CHAR, DOUBLE, FLOAT, INT, LONG, SHORT ->
          type.getKind().toString().toLowerCase();
      default -> null;
    };
  }

  static String typeFqn(final TypeMirror type) {
    if (type instanceof DeclaredType dt) {
      return ((TypeElement) dt.asElement()).getQualifiedName().toString();
    }
    return null;
  }
}
