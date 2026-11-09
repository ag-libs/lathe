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
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
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
// name the file already gives another type is written qualified. One is made per request: the
// file's imports and declared type names are read once, not for every type it names.
public final class SourceTypeNames {

  private final AttributedFileAnalysis analysis;
  private final TypeElement target;
  private final ImportAnalyzer importAnalyzer;
  private final Set<String> importedNames;
  private final Set<String> declaredTypeNames;
  private final Set<Element> memberTypesInScope;

  // `target` is the class the code is written into; none outside every class (a header, an import)
  // leaves only imports and the file's own types to see a name by.
  public SourceTypeNames(final AttributedFileAnalysis analysis, final TypeElement target) {
    this.analysis = analysis;
    this.target = target;
    this.importAnalyzer = new ImportAnalyzer(analysis);
    this.importedNames = importAnalyzer.importedQualifiedNames();
    this.declaredTypeNames = declaredTypeNames(analysis);
    this.memberTypesInScope = memberTypesInScope(analysis, target);
  }

  // The imports the code needs for every type the given types mention, each once: the top-level
  // type each written name starts from (see formatter).
  public List<TextEdit> importEditsFor(final Stream<? extends TypeMirror> types) {
    return types
        .flatMap(SourceTypeNames::typesIn)
        .filter(DeclaredType.class::isInstance)
        .map(type -> ((DeclaredType) type).asElement())
        .filter(TypeElement.class::isInstance)
        .flatMap(type -> importFor((TypeElement) type).stream())
        .distinct()
        .map(importAnalyzer::importEdit)
        .filter(Objects::nonNull)
        .toList();
  }

  // The import line the code naming `type` needs: the top-level type its written name starts from,
  // unless the file declares that type, gives its simple name another one, or needs no line for it
  // (an import it has, java.lang). A nested type is never imported itself.
  public Optional<String> importFor(final TypeElement type) {
    final TypeElement root = nameRoot(type);
    return root.getNestingKind() != NestingKind.MEMBER && !declaredIn(root) && !clashes(root)
        ? Optional.of(root.getQualifiedName().toString()).filter(importAnalyzer::needsImport)
        : Optional.empty();
  }

  // A nested type `target` cannot see by its simple name is written from the nearest enclosing type
  // it can (`ClassModel.Builder`, as Java code usually does; a bare `Module` could be java.lang's,
  // two `Builder`s would clash); the name it starts from is written qualified when the file already
  // gives that simple name another type (by an import, a declaration or its package), else simply.
  public TypeDisplayFormatter formatter() {
    return new TypeDisplayFormatter(analysis.types(), this::sourceName);
  }

  public String sourceName(final TypeElement type) {
    final TypeElement root = nameRoot(type);
    final String rootName =
        (clashes(root) ? root.getQualifiedName() : root.getSimpleName()).toString();
    return rootName
        + type.getQualifiedName().toString().substring(root.getQualifiedName().length());
  }

  // The type a written name starts from: the type itself, or for a nested type `target` cannot see
  // by its simple name (as a member, or through an import of it), the nearest enclosing type it can
  // (or the top-level type).
  private TypeElement nameRoot(final TypeElement type) {
    return type.getNestingKind() == NestingKind.MEMBER
            && !memberTypesInScope.contains(type)
            && !importedNames.contains(type.getQualifiedName().toString())
        ? nameRoot((TypeElement) type.getEnclosingElement())
        : type;
  }

  // The member types `target` sees by their simple names: its own and its enclosing classes',
  // declared or inherited.
  private static Set<Element> memberTypesInScope(
      final AttributedFileAnalysis analysis, final TypeElement target) {
    return Stream.iterate(
            target,
            Objects::nonNull,
            c -> c.getEnclosingElement() instanceof final TypeElement outer ? outer : null)
        .flatMap(c -> analysis.elements().getAllMembers(c).stream())
        .filter(TypeElement.class::isInstance)
        .collect(Collectors.toUnmodifiableSet());
  }

  private boolean clashes(final TypeElement type) {
    final String simpleName = type.getSimpleName().toString();
    final String qualifiedName = type.getQualifiedName().toString();
    return importedNames.stream()
            .anyMatch(
                imported -> !imported.equals(qualifiedName) && imported.endsWith("." + simpleName))
        || declaredTypeNames.contains(simpleName) && !declaredIn(type)
        || inOwnPackage(simpleName).filter(sibling -> !sibling.equals(type)).isPresent();
  }

  // The type of the file's own package with this simple name: it is in scope there without an
  // import, and an import of another one would shadow it for the whole file.
  private Optional<TypeElement> inOwnPackage(final String simpleName) {
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
    return Set.copyOf(names);
  }

  // A type of the file itself is in scope by its simple name, and importing a private one would
  // not even compile.
  private boolean declaredIn(final TypeElement type) {
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
