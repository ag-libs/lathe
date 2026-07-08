package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.server.run.RunTarget;
import io.github.aglibs.lathe.server.run.RunnableKind;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeKind;
import org.eclipse.lsp4j.Range;

final class RunnableScanner extends TreePathScanner<Void, Void> {

  private static final Set<String> TEST_ANNOTATIONS =
      Set.of("Test", "ParameterizedTest", "TestFactory", "RepeatedTest");

  private final Trees trees;
  private final CompilationUnitTree compilationUnit;
  private final String uri;
  private final String moduleRel;
  private final List<RunTarget> targets = new ArrayList<>();
  private final ArrayDeque<ClassScope> classStack = new ArrayDeque<>();
  private final Set<String> classesWithTests = new HashSet<>();
  private boolean packageEmitted;

  private RunnableScanner(
      final Trees trees,
      final CompilationUnitTree compilationUnit,
      final String uri,
      final String moduleRel) {
    this.trees = trees;
    this.compilationUnit = compilationUnit;
    this.uri = uri;
    this.moduleRel = moduleRel;
  }

  static List<RunTarget> scan(
      final Trees trees,
      final CompilationUnitTree compilationUnit,
      final String uri,
      final String moduleRel) {
    final var scanner = new RunnableScanner(trees, compilationUnit, uri, moduleRel);
    scanner.scan(compilationUnit, null);
    return scanner.targets;
  }

  @Override
  public Void visitClass(final ClassTree node, final Void unused) {
    final var scope = new ClassScope(qualifiedName(node), node);
    classStack.push(scope);
    super.visitClass(node, unused);
    classStack.pop();
    if (classesWithTests.contains(scope.qualifiedName())) {
      targets.add(classTarget(scope));
      emitPackageOnce();
    }

    return null;
  }

  @Override
  public Void visitMethod(final MethodTree node, final Void unused) {
    final ClassScope enclosing = classStack.peek();
    if (isMainMethod(node)) {
      targets.add(mainTarget(node, enclosing));
    } else if (isTestMethod(node) && enclosing != null) {
      targets.add(methodTarget(node, enclosing));
      classesWithTests.add(enclosing.qualifiedName());
    }

    return super.visitMethod(node, unused);
  }

  private String qualifiedName(final ClassTree node) {
    final String simpleName = node.getSimpleName().toString();
    final ClassScope enclosing = classStack.peek();
    if (enclosing != null) {
      return "%s$%s".formatted(enclosing.qualifiedName(), simpleName);
    }

    final ExpressionTree pkg = compilationUnit.getPackageName();
    return pkg != null ? "%s.%s".formatted(pkg, simpleName) : simpleName;
  }

  private static boolean isMainMethod(final MethodTree node) {
    final Set<Modifier> modifiers = node.getModifiers().getFlags();
    return node.getName().contentEquals("main")
        && modifiers.contains(Modifier.PUBLIC)
        && modifiers.contains(Modifier.STATIC)
        && isVoidReturn(node)
        && hasSingleStringArrayParam(node);
  }

  private static boolean isVoidReturn(final MethodTree node) {
    return node.getReturnType() instanceof final PrimitiveTypeTree primitive
        && primitive.getPrimitiveTypeKind() == TypeKind.VOID;
  }

  private static boolean hasSingleStringArrayParam(final MethodTree node) {
    if (node.getParameters().size() != 1) {
      return false;
    }

    final Tree type = node.getParameters().getFirst().getType();
    return type instanceof final ArrayTypeTree array && "String".equals(array.getType().toString());
  }

  private static boolean isTestMethod(final MethodTree node) {
    return node.getModifiers().getAnnotations().stream().anyMatch(RunnableScanner::isTestType);
  }

  private static boolean isTestType(final AnnotationTree annotation) {
    final String name = annotation.getAnnotationType().toString();
    final int dot = name.lastIndexOf('.');
    return TEST_ANNOTATIONS.contains(dot >= 0 ? name.substring(dot + 1) : name);
  }

  private static String erasedTypeName(final Tree typeTree) {
    return typeTree instanceof final ParameterizedTypeTree parameterized
        ? parameterized.getType().toString()
        : typeTree.toString();
  }

  private RunTarget mainTarget(final MethodTree node, final ClassScope enclosing) {
    final String fqcn = enclosing != null ? enclosing.qualifiedName() : "";
    return new RunTarget(
        "%s#main".formatted(fqcn), RunnableKind.MAIN, "main", moduleRel, uri, range(node));
  }

  private RunTarget methodTarget(final MethodTree node, final ClassScope enclosing) {
    final String fqcn = enclosing.qualifiedName();
    final String methodName = node.getName().toString();
    final String erasedParams =
        node.getParameters().stream()
            .map(param -> erasedTypeName(param.getType()))
            .collect(Collectors.joining(","));
    final String id = "%s#%s(%s)".formatted(fqcn, methodName, erasedParams);
    return new RunTarget(id, RunnableKind.TEST_METHOD, methodName, moduleRel, uri, range(node));
  }

  private RunTarget classTarget(final ClassScope scope) {
    return new RunTarget(
        scope.qualifiedName(),
        RunnableKind.TEST_CLASS,
        scope.tree().getSimpleName().toString(),
        moduleRel,
        uri,
        range(scope.tree()));
  }

  private void emitPackageOnce() {
    if (packageEmitted) {
      return;
    }

    final ExpressionTree pkg = compilationUnit.getPackageName();
    if (pkg == null) {
      return;
    }

    packageEmitted = true;
    final String name = pkg.toString();
    targets.add(
        new RunTarget(
            name, RunnableKind.TEST_PACKAGE, name, moduleRel, uri, range(compilationUnit)));
  }

  private Range range(final Tree node) {
    return SourceLocator.range(trees, compilationUnit, node);
  }

  private record ClassScope(String qualifiedName, ClassTree tree) {}
}
