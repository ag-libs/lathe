package io.github.aglibs.lathe.server.debug;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Builds the classfile-form JVM descriptors JDI indexes methods and fields by, from javac types —
 * the key that lets the interpreter find the right JDI {@code Method} for a resolved overload.
 * Types are erased first (JDI signatures are erased), so a parameter {@code List<String>} becomes
 * {@code Ljava/util/List;} and a type variable becomes its bound.
 */
final class JvmDescriptor {

  private JvmDescriptor() {}

  /** The erased method descriptor, e.g. {@code (Ljava/lang/String;I)Z}. */
  static String ofMethod(
      final ExecutableElement method, final Types types, final Elements elements) {
    final var descriptor = new StringBuilder("(");
    method.getParameters().forEach(p -> descriptor.append(of(p.asType(), types, elements)));
    return descriptor.append(')').append(of(method.getReturnType(), types, elements)).toString();
  }

  /** The erased JVM type descriptor, e.g. {@code Ljava/lang/String;}, {@code [I}, {@code J}. */
  static String of(final TypeMirror type, final Types types, final Elements elements) {
    return describe(types.erasure(type), elements);
  }

  private static String describe(final TypeMirror type, final Elements elements) {
    return switch (type.getKind()) {
      case BOOLEAN -> "Z";
      case BYTE -> "B";
      case CHAR -> "C";
      case SHORT -> "S";
      case INT -> "I";
      case LONG -> "J";
      case FLOAT -> "F";
      case DOUBLE -> "D";
      case VOID -> "V";
      case ARRAY -> "[" + describe(((ArrayType) type).getComponentType(), elements);
      case DECLARED -> "L" + internalName((DeclaredType) type, elements) + ";";
      default -> throw new EvaluationException("cannot describe type: " + type);
    };
  }

  private static String internalName(final DeclaredType type, final Elements elements) {
    return elements.getBinaryName((TypeElement) type.asElement()).toString().replace('.', '/');
  }
}
