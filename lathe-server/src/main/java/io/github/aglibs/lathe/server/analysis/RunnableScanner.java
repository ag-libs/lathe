package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePathScanner;
import io.github.aglibs.lathe.server.run.RunTarget;
import io.github.aglibs.lathe.server.run.RunnableKind;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.eclipse.lsp4j.Range;

/**
 * Walks the attributed AST for an open file, resolving each declaration's real {@code Element} so
 * parameter-type erasure and binary names match javac's own semantics exactly (mirrors {@link
 * ReferenceTarget}'s {@code buildDescriptor}/{@code getBinaryName} usage) -- unlike its syntax-only
 * siblings {@link DocumentSymbolScanner}/{@link FoldingRangeScanner}, whose purposes never need
 * overload-correct signatures.
 */
final class RunnableScanner extends TreePathScanner<Void, Void> {

  private static final Set<String> TEST_ANNOTATIONS =
      Set.of("Test", "ParameterizedTest", "TestFactory", "RepeatedTest");

  private final AttributedFileAnalysis analysis;
  private final String uri;
  private final String moduleRel;
  private final List<RunTarget> targets = new ArrayList<>();
  private final ArrayDeque<TypeElement> classStack = new ArrayDeque<>();
  private final Set<String> classesWithTests = new HashSet<>();
  private boolean packageEmitted;

  private RunnableScanner(
      final AttributedFileAnalysis analysis, final String uri, final String moduleRel) {
    this.analysis = analysis;
    this.uri = uri;
    this.moduleRel = moduleRel;
  }

  static List<RunTarget> scan(
      final AttributedFileAnalysis analysis, final String uri, final String moduleRel) {
    final var scanner = new RunnableScanner(analysis, uri, moduleRel);
    scanner.scan(analysis.tree(), null);
    return scanner.targets;
  }

  @Override
  public Void visitClass(final ClassTree node, final Void unused) {
    final var element = analysis.trees().getElement(getCurrentPath());
    if (!(element instanceof final TypeElement typeElement)) {
      return super.visitClass(node, unused);
    }

    classStack.push(typeElement);
    super.visitClass(node, unused);
    classStack.pop();
    final String binaryName = binaryName(typeElement);
    if (classesWithTests.contains(binaryName)) {
      targets.add(classTarget(node, typeElement, binaryName));
      emitPackageOnce();
    }

    return null;
  }

  @Override
  public Void visitMethod(final MethodTree node, final Void unused) {
    final var element = analysis.trees().getElement(getCurrentPath());
    if (!(element instanceof final ExecutableElement executable)) {
      return super.visitMethod(node, unused);
    }

    final TypeElement enclosing = classStack.peek();
    if (isMainMethod(executable)) {
      targets.add(mainTarget(node, enclosing));
    } else if (isTestMethod(executable) && enclosing != null) {
      targets.add(methodTarget(node, executable, enclosing));
      classesWithTests.add(binaryName(enclosing));
    }

    return super.visitMethod(node, unused);
  }

  private String binaryName(final TypeElement type) {
    return analysis.elements().getBinaryName(type).toString();
  }

  /**
   * Matches both the classic {@code public static void main(String[])} and, since JDK 21+'s relaxed
   * launch protocol (JEP 512, finalized in JDK 25), a bare instance {@code void main()} -- no
   * {@code public}/{@code static} required, and either zero parameters or a single {@code
   * String[]}.
   */
  private static boolean isMainMethod(final ExecutableElement element) {
    if (!element.getSimpleName().contentEquals("main")
        || element.getReturnType().getKind() != TypeKind.VOID) {
      return false;
    }

    return switch (element.getParameters().size()) {
      case 0 -> true;
      case 1 -> isStringArray(element.getParameters().getFirst().asType());
      default -> false;
    };
  }

  private static boolean isStringArray(final TypeMirror type) {
    return type.getKind() == TypeKind.ARRAY
        && ((ArrayType) type).getComponentType().toString().equals("java.lang.String");
  }

  private static boolean isTestMethod(final ExecutableElement element) {
    return element.getAnnotationMirrors().stream().anyMatch(RunnableScanner::isTestAnnotation);
  }

  private static boolean isTestAnnotation(final AnnotationMirror mirror) {
    return mirror.getAnnotationType().asElement() instanceof final TypeElement type
        && TEST_ANNOTATIONS.contains(type.getSimpleName().toString());
  }

  private RunTarget mainTarget(final MethodTree node, final TypeElement enclosing) {
    final String fqcn = enclosing != null ? binaryName(enclosing) : "";
    return new RunTarget(
        "%s#main".formatted(fqcn), RunnableKind.MAIN, "main", moduleRel, uri, range(node));
  }

  private RunTarget methodTarget(
      final MethodTree node, final ExecutableElement element, final TypeElement enclosing) {
    final String erasedParams =
        element.getParameters().stream()
            .map(param -> analysis.types().erasure(param.asType()).toString())
            .collect(Collectors.joining(","));
    final String methodName = element.getSimpleName().toString();
    final String id = "%s#%s(%s)".formatted(binaryName(enclosing), methodName, erasedParams);
    return new RunTarget(id, RunnableKind.TEST_METHOD, methodName, moduleRel, uri, range(node));
  }

  private RunTarget classTarget(
      final ClassTree node, final TypeElement element, final String binaryName) {
    return new RunTarget(
        binaryName,
        RunnableKind.TEST_CLASS,
        element.getSimpleName().toString(),
        moduleRel,
        uri,
        range(node));
  }

  private void emitPackageOnce() {
    if (packageEmitted) {
      return;
    }

    final ExpressionTree pkg = analysis.tree().getPackageName();
    if (pkg == null) {
      return;
    }

    packageEmitted = true;
    final String name = pkg.toString();
    targets.add(
        new RunTarget(
            name, RunnableKind.TEST_PACKAGE, name, moduleRel, uri, range(analysis.tree())));
  }

  private Range range(final Tree node) {
    return SourceLocator.range(analysis.trees(), analysis.tree(), node);
  }
}
