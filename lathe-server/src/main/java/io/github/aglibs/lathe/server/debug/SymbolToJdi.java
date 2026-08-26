package io.github.aglibs.lathe.server.debug;

import java.util.Optional;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;

/**
 * Bridges a javac-resolved {@link Element} to a {@link JdiRef} the interpreter uses to fetch its
 * value from the suspended frame (Stage 2 of expression evaluation). Pure over javac symbols — the
 * JDI lookup itself lives in the interpreter — so it is testable without a live VM. v1 bridges
 * reads only; a method or any other symbol yields empty (unsupported until v2).
 */
final class SymbolToJdi {

  private SymbolToJdi() {}

  static Optional<JdiRef> toRef(final Element element, final Elements elements) {
    return switch (element) {
      case VariableElement variable -> Optional.of(variableRef(variable, elements));
      case TypeElement type ->
          Optional.of(new JdiRef.Type(elements.getBinaryName(type).toString()));
      case null, default -> Optional.empty();
    };
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
