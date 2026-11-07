package io.github.aglibs.lathe.analysis;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;

public final class SourceParser implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger(SourceParser.class.getName());

  private final StandardJavaFileManager fm;

  public SourceParser() {
    this.fm = JavaSourceCompiler.createFileManager();
  }

  <T> Optional<T> parseFile(
      final Path sourceFile, final BiFunction<Trees, CompilationUnitTree, T> fn) {
    return parse(fm.getJavaFileObjects(sourceFile).iterator().next(), fn);
  }

  public static boolean isSyntheticName(final String name) {
    return name.matches("arg\\d+");
  }

  List<String> resolveParamNames(final ExecutableElement method, final List<Path> sourceRoots) {
    final boolean anySynthetic =
        method.getParameters().stream()
            .anyMatch(p -> isSyntheticName(p.getSimpleName().toString()));
    if (!anySynthetic) {
      return null;
    }

    return parseDeclaration(
            method,
            sourceRoots,
            (trees, path) -> {
              if (!(path.getLeaf() instanceof MethodTree mt)) {
                return null;
              }

              return mt.getParameters().stream().map(v -> v.getName().toString()).toList();
            })
        .orElse(null);
  }

  <T> Optional<T> parseDeclaration(
      final Element element,
      final List<Path> sourceRoots,
      final BiFunction<Trees, TreePath, T> fn) {
    return DefinitionLocator.findSourceFile(element, sourceRoots)
        .flatMap(
            file -> {
              LOG.fine(() -> "[source] %s → %s".formatted(element, file));
              return parseFile(
                  file,
                  (trees, cu) -> {
                    final var path = declarationPath(cu, element);
                    return path != null ? fn.apply(trees, path) : null;
                  });
            });
  }

  public <T> Optional<T> parseContent(
      final String uri, final String content, final BiFunction<Trees, CompilationUnitTree, T> fn) {
    final var jfo =
        new SimpleJavaFileObject(URI.create(uri), JavaFileObject.Kind.SOURCE) {
          @Override
          public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
            return content;
          }
        };
    return parse(jfo, fn);
  }

  private <T> Optional<T> parse(
      final JavaFileObject jfo, final BiFunction<Trees, CompilationUnitTree, T> fn) {
    final var collector = new DiagnosticCollector<JavaFileObject>();
    final var task =
        (JavacTask)
            JavaSourceCompiler.COMPILER.getTask(null, fm, collector, null, null, List.of(jfo));
    try {
      final CompilationUnitTree cu = task.parse().iterator().next();
      final T result = fn.apply(Trees.instance(task), cu);
      final var diags = collector.getDiagnostics();
      if (!diags.isEmpty()) {
        LOG.fine(() -> "[parse] diags: %s".formatted(diagSummary(diags)));
      }
      return Optional.ofNullable(result);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String diagSummary(
      final List<? extends Diagnostic<? extends JavaFileObject>> diags) {
    return diags.stream()
        .collect(Collectors.groupingBy(Diagnostic::getKind, Collectors.counting()))
        .entrySet()
        .stream()
        .map(e -> "%s=%d".formatted(e.getKey(), e.getValue()))
        .collect(Collectors.joining(" "));
  }

  @Override
  public void close() {
    try {
      fm.close();
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[parser] failed to close fm");
    }
  }

  // The declaration of target in a parse-only tree (a dependency's source, no attribution, so no
  // elements to compare): a type, method, constructor or field whose name, enclosing types and, for
  // a method, parameter types (by erased simple name) match. Two overloads whose parameter types
  // share a simple name from different packages cannot be told apart this way.
  public static TreePath declarationPath(final CompilationUnitTree cu, final Element target) {
    if (target == null) {
      return null;
    }

    final TypeChain owner = TypeChain.of(target);
    final var result = new AtomicReference<TreePath>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitClass(final ClassTree tree, final Void unused) {
        if ((target.getKind().isClass() || target.getKind().isInterface())
            && owner.matches(getCurrentPath())) {
          result.set(getCurrentPath());
        }
        return super.visitClass(tree, unused);
      }

      @Override
      public Void visitMethod(final MethodTree tree, final Void unused) {
        final boolean nameMatch =
            target.getKind() == ElementKind.CONSTRUCTOR
                ? tree.getName().contentEquals("<init>")
                : tree.getName().contentEquals(DeclarationNames.declarationName(target));
        if ((target.getKind() == ElementKind.METHOD || target.getKind() == ElementKind.CONSTRUCTOR)
            && nameMatch
            && owner.matches(getCurrentPath().getParentPath())
            && matchParameters(
                tree.getParameters(), ((ExecutableElement) target).getParameters())) {
          result.set(getCurrentPath());
        }
        return super.visitMethod(tree, unused);
      }

      @Override
      public Void visitVariable(final VariableTree tree, final Void unused) {
        if ((target.getKind() == ElementKind.FIELD || target.getKind() == ElementKind.ENUM_CONSTANT)
            && tree.getName().contentEquals(target.getSimpleName())
            && getCurrentPath().getParentPath().getLeaf() instanceof ClassTree
            && owner.matches(getCurrentPath().getParentPath())) {
          result.set(getCurrentPath());
        }
        return super.visitVariable(tree, unused);
      }
    }.scan(cu, null);
    return result.get();
  }

  // The simple names of the types enclosing a declaration, outermost first (the type itself
  // included, for a type). Exact when the outermost is a package member; a local or anonymous
  // type's chain stops at its method, so it matches the end of a tree's chain.
  private record TypeChain(List<String> names, boolean fromPackage) {

    TypeChain {
      names = List.copyOf(names);
    }

    static TypeChain of(final Element declaration) {
      final var names = new ArrayList<String>();
      Element current =
          declaration.getKind().isClass() || declaration.getKind().isInterface()
              ? declaration
              : declaration.getEnclosingElement();
      while (current instanceof final TypeElement type) {
        names.add(0, type.getSimpleName().toString());
        current = type.getEnclosingElement();
      }

      return new TypeChain(names, current instanceof PackageElement);
    }

    // Whether the classes on the path (the path's own class included) are this chain.
    boolean matches(final TreePath path) {
      final var treeNames = new ArrayList<String>();
      for (TreePath p = path; p != null; p = p.getParentPath()) {
        if (p.getLeaf() instanceof final ClassTree type) {
          treeNames.add(0, type.getSimpleName().toString());
        }
      }

      return fromPackage
          ? treeNames.equals(names)
          : treeNames.size() >= names.size()
              && treeNames.subList(treeNames.size() - names.size(), treeNames.size()).equals(names);
    }
  }

  private static boolean matchParameters(
      final List<? extends VariableTree> treeParams,
      final List<? extends VariableElement> targetParams) {
    if (treeParams.size() != targetParams.size()) {
      return false;
    }

    return IntStream.range(0, treeParams.size())
        .allMatch(
            i ->
                simpleName(treeParams.get(i).getType())
                    .equals(simpleName(targetParams.get(i).asType())));
  }

  // A parameter type as written, by its erased simple name: List<String> is List, a.b.C[] is C[],
  // and a varargs parameter is an array.
  private static String simpleName(final Tree type) {
    if (type instanceof final IdentifierTree identifier) {
      return identifier.getName().toString();
    }

    if (type instanceof final MemberSelectTree select) {
      return select.getIdentifier().toString();
    }

    if (type instanceof final ArrayTypeTree array) {
      return "%s[]".formatted(simpleName(array.getType()));
    }

    if (type instanceof final ParameterizedTypeTree parameterized) {
      return simpleName(parameterized.getType());
    }

    if (type instanceof final AnnotatedTypeTree annotated) {
      return simpleName(annotated.getUnderlyingType());
    }

    if (type instanceof final PrimitiveTypeTree primitive) {
      return primitive.getPrimitiveTypeKind().name().toLowerCase(Locale.ROOT);
    }

    return type.toString();
  }

  // The element's parameter type by the same rule.
  private static String simpleName(final TypeMirror type) {
    if (type instanceof final ArrayType array) {
      return "%s[]".formatted(simpleName(array.getComponentType()));
    }

    if (type instanceof final DeclaredType declared) {
      return declared.asElement().getSimpleName().toString();
    }

    if (type instanceof final TypeVariable variable) {
      return variable.asElement().getSimpleName().toString();
    }

    return type.getKind().isPrimitive()
        ? type.getKind().name().toLowerCase(Locale.ROOT)
        : type.toString();
  }
}
