package io.github.aglibs.lathe.analysis.internal;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import io.github.aglibs.lathe.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.analysis.ImportAnalyzer;
import io.github.aglibs.lathe.analysis.TypeDisplayFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import org.eclipse.lsp4j.TextEdit;

// How code written into a class of the file names a type, and the import that name needs: shared by
// the refactors and the import fixes that write a type and by completion, so a nested type is
// written the same way everywhere (from a type the code can name, never imported itself) and a
// name the file already gives another type is written qualified.
public final class SourceTypeNames {

  private SourceTypeNames() {}

  // The imports code written into `target` needs for every type the given types mention, each once:
  // the top-level type each written name starts from (see sourceTypeFormatter).
  public static List<TextEdit> importEditsFor(
      final AttributedFileAnalysis analysis,
      final TypeElement target,
      final Stream<? extends TypeMirror> types) {
    final var importAnalyzer = new ImportAnalyzer(analysis);
    return types
        .flatMap(SourceTypeNames::typesIn)
        .filter(DeclaredType.class::isInstance)
        .map(type -> ((DeclaredType) type).asElement())
        .filter(TypeElement.class::isInstance)
        .flatMap(type -> importFor(analysis, target, (TypeElement) type).stream())
        .distinct()
        .map(importAnalyzer::importEdit)
        .filter(Objects::nonNull)
        .toList();
  }

  // The type to import for code in `target` naming `type`: the top-level type its written name
  // starts from, unless the file declares that type or gives its simple name another one. A nested
  // type is never imported itself. The import may still be one the file needs no line for (an
  // import it has, java.lang); ImportAnalyzer decides that.
  public static Optional<String> importFor(
      final AttributedFileAnalysis analysis, final TypeElement target, final TypeElement type) {
    final TypeElement root = nameRoot(analysis, target, type);
    return root.getNestingKind() != NestingKind.MEMBER
            && !declaredIn(analysis, root)
            && !clashes(analysis, root)
        ? Optional.of(root.getQualifiedName().toString())
        : Optional.empty();
  }

  // For code written into `target`, a class of the file: a nested type `target` cannot see by its
  // simple name is written from the nearest enclosing type it can (`ClassModel.Builder`, as Java
  // code usually does; a bare `Module` could be java.lang's, two `Builder`s would clash); the name
  // it starts from is written qualified when the file already gives that simple name another type
  // (by an import, a declaration or its package), else simply.
  public static TypeDisplayFormatter sourceTypeFormatter(
      final AttributedFileAnalysis analysis, final TypeElement target) {
    return new TypeDisplayFormatter(analysis.types(), type -> sourceName(analysis, target, type));
  }

  public static String sourceName(
      final AttributedFileAnalysis analysis, final TypeElement target, final TypeElement type) {
    final TypeElement root = nameRoot(analysis, target, type);
    final String rootName =
        (clashes(analysis, root) ? root.getQualifiedName() : root.getSimpleName()).toString();
    return rootName
        + type.getQualifiedName().toString().substring(root.getQualifiedName().length());
  }

  // The type a written name starts from: the type itself, or for a nested type `target` cannot see
  // by its simple name (as a member, or through an import of it), the nearest enclosing type it can
  // (or the top-level type).
  private static TypeElement nameRoot(
      final AttributedFileAnalysis analysis, final TypeElement target, final TypeElement type) {
    return type.getNestingKind() == NestingKind.MEMBER
            && !memberTypeInScope(analysis, target, type)
            && !importedNames(analysis).contains(type.getQualifiedName().toString())
        ? nameRoot(analysis, target, (TypeElement) type.getEnclosingElement())
        : type;
  }

  // A member type `target` sees by its simple name: one of its own or an enclosing class's members,
  // declared or inherited.
  private static boolean memberTypeInScope(
      final AttributedFileAnalysis analysis, final TypeElement target, final TypeElement type) {
    return Stream.iterate(
            target,
            Objects::nonNull,
            c -> c.getEnclosingElement() instanceof final TypeElement outer ? outer : null)
        .anyMatch(c -> analysis.elements().getAllMembers(c).contains(type));
  }

  private static boolean clashes(final AttributedFileAnalysis analysis, final TypeElement type) {
    final String simpleName = type.getSimpleName().toString();
    final String qualifiedName = type.getQualifiedName().toString();
    return importedNames(analysis).stream()
            .anyMatch(
                imported -> !imported.equals(qualifiedName) && imported.endsWith("." + simpleName))
        || declaredTypeNames(analysis).contains(simpleName) && !declaredIn(analysis, type)
        || inOwnPackage(analysis, simpleName).filter(sibling -> !sibling.equals(type)).isPresent();
  }

  private static Set<String> importedNames(final AttributedFileAnalysis analysis) {
    return new ImportAnalyzer(analysis).importedQualifiedNames();
  }

  // The type of the file's own package with this simple name: it is in scope there without an
  // import, and an import of another one would shadow it for the whole file.
  private static Optional<TypeElement> inOwnPackage(
      final AttributedFileAnalysis analysis, final String simpleName) {
    final ExpressionTree packageName = analysis.tree().getPackageName();
    final String name =
        packageName == null ? simpleName : "%s.%s".formatted(packageName, simpleName);
    return Optional.ofNullable(analysis.elements().getTypeElement(name));
  }

  private static Set<String> declaredTypeNames(final AttributedFileAnalysis analysis) {
    final Set<String> names = new HashSet<>();
    new TreeScanner<Void, Void>() {
      @Override
      public Void visitClass(final ClassTree tree, final Void unused) {
        names.add(tree.getSimpleName().toString());
        return super.visitClass(tree, unused);
      }
    }.scan(analysis.tree(), null);
    return names;
  }

  // A type of the file itself is in scope by its simple name, and importing a private one would
  // not even compile.
  private static boolean declaredIn(final AttributedFileAnalysis analysis, final TypeElement type) {
    final TreePath path = analysis.trees().getPath(type);
    return path != null && path.getCompilationUnit() == analysis.tree();
  }

  // A type and every type in it: generic arguments, array components, wildcard bounds.
  public static Stream<TypeMirror> typesIn(final TypeMirror type) {
    final Stream<? extends TypeMirror> nested;
    if (type instanceof final DeclaredType declared) {
      nested = declared.getTypeArguments().stream();
    } else if (type instanceof final ArrayType array) {
      nested = Stream.of(array.getComponentType());
    } else if (type instanceof final WildcardType wildcard) {
      nested =
          Stream.of(wildcard.getExtendsBound(), wildcard.getSuperBound()).filter(Objects::nonNull);
    } else {
      nested = Stream.empty();
    }

    return Stream.concat(Stream.of(type), nested.flatMap(SourceTypeNames::typesIn));
  }
}
