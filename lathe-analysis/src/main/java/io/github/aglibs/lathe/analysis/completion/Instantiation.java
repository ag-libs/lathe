package io.github.aglibs.lathe.analysis.completion;

import com.sun.source.tree.Scope;
import io.github.aglibs.lathe.analysis.AttributedFileAnalysis;
import java.util.List;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.ElementFilter;

// What constructor completion may offer after `new` or as `::new`, one rule for every path.
final class Instantiation {

  private Instantiation() {}

  // `new X()` or `X::new` is legal: a concrete class or record. A sealed class may be concrete:
  // only an anonymous subclass of it (`new X() {}`) is illegal.
  static boolean directlyInstantiable(final TypeElement typeEl) {
    return (typeEl.getKind() == ElementKind.CLASS || typeEl.getKind() == ElementKind.RECORD)
        && !typeEl.getModifiers().contains(Modifier.ABSTRACT);
  }

  // The constructors the scope may call; all of them where there is no scope to check from.
  static List<ExecutableElement> accessibleConstructors(
      final DeclaredType type, final AttributedFileAnalysis analysis, final Scope scope) {
    return ElementFilter.constructorsIn(type.asElement().getEnclosedElements()).stream()
        .filter(constructor -> accessible(constructor, type, analysis, scope))
        .toList();
  }

  private static boolean accessible(
      final ExecutableElement constructor,
      final DeclaredType type,
      final AttributedFileAnalysis analysis,
      final Scope scope) {
    if (scope == null) {
      return true;
    }

    try {
      return analysis.trees().isAccessible(scope, constructor, type);
    } catch (final IllegalArgumentException ignored) {
      return true;
    }
  }
}
