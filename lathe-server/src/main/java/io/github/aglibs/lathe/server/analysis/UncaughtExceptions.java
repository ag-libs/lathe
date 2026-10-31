package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.UnionTypeTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

// The checked exceptions code can throw and does not catch itself, for the `throws` of a method the
// code moves into: each call's and constructor's exceptions as instantiated there (so
// `orElseThrow(X::new)` throws X, not a type variable), each `throw`'s type, and a try resource's
// close(); minus those a try inside the code catches, over its body and its resources. Lambda and
// class bodies are not entered: what they throw is theirs.
final class UncaughtExceptions {

  private UncaughtExceptions() {}

  static List<TypeMirror> of(final List<TreePath> roots, final AttributedFileAnalysis analysis) {
    final Types types = analysis.types();
    final TypeMirror runtime = typeOf(analysis, "java.lang.RuntimeException");
    final TypeMirror error = typeOf(analysis, "java.lang.Error");
    final Set<Tree> rootLeaves = Set.copyOf(roots.stream().map(TreePath::getLeaf).toList());
    final var thrown = new ArrayList<TypeMirror>();
    final var scanner =
        new TreePathScanner<Void, Void>() {
          @Override
          public Void visitLambdaExpression(final LambdaExpressionTree tree, final Void unused) {
            return null;
          }

          @Override
          public Void visitClass(final ClassTree tree, final Void unused) {
            return null;
          }

          @Override
          public Void visitMethodInvocation(final MethodInvocationTree tree, final Void unused) {
            if (analysis
                    .trees()
                    .getTypeMirror(new TreePath(getCurrentPath(), tree.getMethodSelect()))
                instanceof final ExecutableType method) {
              method.getThrownTypes().forEach(type -> add(type, getCurrentPath()));
            }

            return super.visitMethodInvocation(tree, unused);
          }

          @Override
          public Void visitNewClass(final NewClassTree tree, final Void unused) {
            if (analysis.trees().getElement(getCurrentPath())
                instanceof final ExecutableElement constructor) {
              constructor.getThrownTypes().forEach(type -> add(type, getCurrentPath()));
            }

            scan(tree.getArguments(), unused);
            return null;
          }

          @Override
          public Void visitThrow(final ThrowTree tree, final Void unused) {
            add(
                analysis
                    .trees()
                    .getTypeMirror(new TreePath(getCurrentPath(), tree.getExpression())),
                getCurrentPath());
            return super.visitThrow(tree, unused);
          }

          @Override
          public Void visitTry(final TryTree tree, final Void unused) {
            final TreePath block = new TreePath(getCurrentPath(), tree.getBlock());
            tree.getResources().stream()
                .map(
                    resource ->
                        analysis.trees().getTypeMirror(new TreePath(getCurrentPath(), resource)))
                .flatMap(resourceType -> closeExceptions(resourceType, analysis))
                .toList()
                .forEach(type -> add(type, block));
            return super.visitTry(tree, unused);
          }

          private void add(final TypeMirror type, final TreePath site) {
            if (type != null
                && !types.isSubtype(type, runtime)
                && !types.isSubtype(type, error)
                && !caughtWithin(type, site, rootLeaves, analysis)) {
              thrown.add(type);
            }
          }
        };
    roots.forEach(root -> scanner.scan(root, null));
    return withoutSubsumed(thrown, types);
  }

  private static Stream<TypeMirror> closeExceptions(
      final TypeMirror resourceType, final AttributedFileAnalysis analysis) {
    if (!(resourceType instanceof final DeclaredType declared)
        || !(declared.asElement() instanceof final TypeElement element)) {
      return Stream.empty();
    }

    return analysis.elements().getAllMembers(element).stream()
        .filter(member -> member.getKind() == ElementKind.METHOD)
        .map(ExecutableElement.class::cast)
        .filter(method -> method.getSimpleName().contentEquals("close"))
        .filter(method -> method.getParameters().isEmpty())
        .flatMap(
            method ->
                ((ExecutableType) analysis.types().asMemberOf(declared, method))
                    .getThrownTypes().stream());
  }

  // Whether a try between the throwing site and the code's own roots catches the type: one whose
  // body or resource list holds the site, with a catch of a supertype (union alternatives too).
  private static boolean caughtWithin(
      final TypeMirror type,
      final TreePath site,
      final Set<Tree> rootLeaves,
      final AttributedFileAnalysis analysis) {
    for (TreePath path = site;
        path.getParentPath() != null && !rootLeaves.contains(path.getLeaf());
        path = path.getParentPath()) {
      final TreePath parent = path.getParentPath();
      final Tree leaf = path.getLeaf();
      if (parent.getLeaf() instanceof final TryTree tryTree
          && (tryTree.getBlock() == leaf || tryTree.getResources().contains(leaf))
          && catches(tryTree, parent, type, analysis)) {
        return true;
      }
    }
    return false;
  }

  private static boolean catches(
      final TryTree tryTree,
      final TreePath tryPath,
      final TypeMirror type,
      final AttributedFileAnalysis analysis) {
    return tryTree.getCatches().stream()
        .anyMatch(
            catchTree ->
                alternatives(catchTree).stream()
                    .map(
                        alternative ->
                            analysis
                                .trees()
                                .getTypeMirror(
                                    new TreePath(new TreePath(tryPath, catchTree), alternative)))
                    .anyMatch(
                        caught -> caught != null && analysis.types().isSubtype(type, caught)));
  }

  private static List<? extends Tree> alternatives(final CatchTree catchTree) {
    final Tree type = catchTree.getParameter().getType();
    return type instanceof final UnionTypeTree union ? union.getTypeAlternatives() : List.of(type);
  }

  // Each type once, and none that another listed type already covers.
  private static List<TypeMirror> withoutSubsumed(
      final List<TypeMirror> thrown, final Types types) {
    final List<TypeMirror> distinct = new ArrayList<>();
    for (final TypeMirror type : thrown) {
      if (distinct.stream().noneMatch(seen -> types.isSameType(seen, type))) {
        distinct.add(type);
      }
    }

    return distinct.stream()
        .filter(
            type ->
                distinct.stream().noneMatch(other -> other != type && types.isSubtype(type, other)))
        .toList();
  }

  private static TypeMirror typeOf(final AttributedFileAnalysis analysis, final String name) {
    return analysis.elements().getTypeElement(name).asType();
  }
}
