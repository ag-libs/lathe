package io.github.aglibs.lathe.server.debug;

import java.util.Optional;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Bridges a javac-resolved {@link Element} to a {@link JdiRef} the interpreter uses to reach it in
 * the suspended frame (Stage 2 of expression evaluation). Pure over javac symbols — the JDI lookup
 * itself lives in the interpreter — so it is testable without a live VM: reads become Local/Field,
 * types become Type, and methods/constructors become Method (by declaring type + name + erased JVM
 * signature). Any other symbol yields empty.
 */
final class SymbolToJdi {

  private SymbolToJdi() {}

  static Optional<JdiRef> toRef(final Element element, final Types types, final Elements elements) {
    return switch (element) {
      case VariableElement variable -> Optional.of(variableRef(variable, elements));
      case TypeElement type ->
          Optional.of(new JdiRef.Type(elements.getBinaryName(type).toString()));
      case ExecutableElement method -> Optional.of(methodRef(method, types, elements));
      case null, default -> Optional.empty();
    };
  }

  // A method/constructor is matched by its declaring type, name (<init> for constructors), and
  // erased JVM signature -- the key JDI resolves overloads by.
  private static JdiRef methodRef(
      final ExecutableElement method, final Types types, final Elements elements) {
    final var declaring = (TypeElement) method.getEnclosingElement();
    return new JdiRef.Method(
        elements.getBinaryName(declaring).toString(),
        method.getSimpleName().toString(),
        JvmDescriptor.ofMethod(method, types, elements),
        method.getModifiers().contains(Modifier.STATIC));
  }

  // A field/enum-constant becomes a Field (by declaring type + name); every other variable kind
  // (local, parameter, exception/resource/pattern binding) is matched by name in the frame.
  private static JdiRef variableRef(final VariableElement variable, final Elements elements) {
    if (!variable.getKind().isField()) {
      return new JdiRef.Local(variable.getSimpleName().toString());
    }

    final var declaring = (TypeElement) variable.getEnclosingElement();
    return new JdiRef.Field(
        elements.getBinaryName(declaring).toString(),
        variable.getSimpleName().toString(),
        variable.getModifiers().contains(Modifier.STATIC));
  }
}
