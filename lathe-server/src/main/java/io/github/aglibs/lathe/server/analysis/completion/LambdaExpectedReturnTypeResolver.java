package io.github.aglibs.lathe.server.analysis.completion;

import com.sun.source.tree.*;
import com.sun.source.util.TreePath;
import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

final class LambdaExpectedReturnTypeResolver {

  private enum ResultShape {
    INVOCATION_ITSELF(null),
    RECEIVER_OF_TO_LIST("toList"),
    RECEIVER_OF_COLLECT("collect");

    private final String methodName;

    ResultShape(final String methodName) {
      this.methodName = methodName;
    }
  }

  private enum Projection {
    FIRST_TYPE_ARGUMENT,
    MAP_VALUE_TYPE,
    MAP_VALUE_FIRST_TYPE_ARGUMENT
  }

  private record Rule(
      String ownerQualifiedName,
      String methodName,
      int lambdaArgumentIndex,
      ResultShape resultShape,
      Projection projection) {

    Rule {
      ValidCheck.check()
          .notBlank(ownerQualifiedName, "ownerQualifiedName")
          .notBlank(methodName, "methodName")
          .isNonNegative(lambdaArgumentIndex, "lambdaArgumentIndex")
          .notNull(resultShape, "resultShape")
          .notNull(projection, "projection")
          .validate();
    }
  }

  private static final List<Rule> RULES =
      List.of(
          // Optional rules
          new Rule(
              "java.util.Optional",
              "map",
              0,
              ResultShape.INVOCATION_ITSELF,
              Projection.FIRST_TYPE_ARGUMENT),
          new Rule(
              "java.util.Optional",
              "flatMap",
              0,
              ResultShape.INVOCATION_ITSELF,
              Projection.FIRST_TYPE_ARGUMENT),

          // Stream rules mapping directly or toList
          new Rule(
              "java.util.stream.Stream",
              "map",
              0,
              ResultShape.RECEIVER_OF_TO_LIST,
              Projection.FIRST_TYPE_ARGUMENT),
          new Rule(
              "java.util.stream.Stream",
              "flatMap",
              0,
              ResultShape.RECEIVER_OF_TO_LIST,
              Projection.FIRST_TYPE_ARGUMENT),
          new Rule(
              "java.util.stream.Stream",
              "mapToObj",
              0,
              ResultShape.RECEIVER_OF_TO_LIST,
              Projection.FIRST_TYPE_ARGUMENT),

          // Stream rules mapping to collect
          new Rule(
              "java.util.stream.Stream",
              "map",
              0,
              ResultShape.RECEIVER_OF_COLLECT,
              Projection.FIRST_TYPE_ARGUMENT),
          new Rule(
              "java.util.stream.Stream",
              "map",
              0,
              ResultShape.RECEIVER_OF_COLLECT,
              Projection.MAP_VALUE_TYPE),
          new Rule(
              "java.util.stream.Stream",
              "map",
              0,
              ResultShape.RECEIVER_OF_COLLECT,
              Projection.MAP_VALUE_FIRST_TYPE_ARGUMENT),

          // CompletionStage / CompletableFuture rules
          new Rule(
              "java.util.concurrent.CompletionStage",
              "thenApply",
              0,
              ResultShape.INVOCATION_ITSELF,
              Projection.FIRST_TYPE_ARGUMENT),
          new Rule(
              "java.util.concurrent.CompletionStage",
              "thenApplyAsync",
              0,
              ResultShape.INVOCATION_ITSELF,
              Projection.FIRST_TYPE_ARGUMENT),
          new Rule(
              "java.util.concurrent.CompletionStage",
              "thenCompose",
              0,
              ResultShape.INVOCATION_ITSELF,
              Projection.FIRST_TYPE_ARGUMENT),
          new Rule(
              "java.util.concurrent.CompletionStage",
              "thenComposeAsync",
              0,
              ResultShape.INVOCATION_ITSELF,
              Projection.FIRST_TYPE_ARGUMENT),
          new Rule(
              "java.util.concurrent.CompletionStage",
              "handle",
              0,
              ResultShape.INVOCATION_ITSELF,
              Projection.FIRST_TYPE_ARGUMENT),
          new Rule(
              "java.util.concurrent.CompletionStage",
              "handleAsync",
              0,
              ResultShape.INVOCATION_ITSELF,
              Projection.FIRST_TYPE_ARGUMENT),
          new Rule(
              "java.util.concurrent.CompletionStage",
              "exceptionally",
              0,
              ResultShape.INVOCATION_ITSELF,
              Projection.FIRST_TYPE_ARGUMENT));

  private LambdaExpectedReturnTypeResolver() {}

  static Optional<TypeMirror> resolve(
      final TreePath lambdaPath, final AttributedFileAnalysis snapshot) {
    if (snapshot.tree() == null) {
      return Optional.empty();
    }

    TreePath current = lambdaPath;
    while (current != null
        && current.getParentPath() != null
        && !(current.getParentPath().getLeaf() instanceof MethodInvocationTree)) {
      current = current.getParentPath();
    }

    if (current == null || current.getParentPath() == null) {
      return Optional.empty();
    }

    final var invocationPath = current.getParentPath();
    final var invocation = (MethodInvocationTree) invocationPath.getLeaf();
    final var arguments = invocation.getArguments();
    final var currentLeaf = current.getLeaf();
    final int argIndex =
        IntStream.range(0, arguments.size())
            .filter(i -> arguments.get(i) == currentLeaf)
            .findFirst()
            .orElse(-1);
    if (argIndex < 0) {
      return Optional.empty();
    }

    final var methodName = methodSelectName(invocation.getMethodSelect());
    if (methodName == null) {
      return Optional.empty();
    }

    final var receiverType = resolveReceiverType(invocationPath, invocation, snapshot);
    if (!(receiverType instanceof final DeclaredType declaredReceiver)) {
      return Optional.empty();
    }

    final var receiverElement = snapshot.types().asElement(declaredReceiver);
    if (!(receiverElement instanceof final TypeElement typeElement)) {
      return Optional.empty();
    }

    for (final var rule : RULES) {
      if (!rule.methodName().equals(methodName) || rule.lambdaArgumentIndex() != argIndex) {
        continue;
      }

      if (!isSubtypeOf(typeElement, rule.ownerQualifiedName(), snapshot)) {
        continue;
      }

      final var expectedSourcePath = resolveExpectedSourcePath(invocationPath, rule.resultShape());
      if (expectedSourcePath == null) {
        continue;
      }

      final var expectedType = enclosingExpectedType(expectedSourcePath, snapshot);
      if (expectedType == null) {
        continue;
      }

      final var projectedType = applyProjection(expectedType, rule.projection());
      if (projectedType != null) {
        return Optional.of(projectedType);
      }
    }

    return Optional.empty();
  }

  private static String methodSelectName(final Tree methodSelect) {
    if (methodSelect instanceof final IdentifierTree id) {
      return id.getName().toString();
    }

    if (methodSelect instanceof final MemberSelectTree ms) {
      return ms.getIdentifier().toString();
    }

    return null;
  }

  private static TypeMirror resolveReceiverType(
      final TreePath invocationPath,
      final MethodInvocationTree invocation,
      final AttributedFileAnalysis snapshot) {
    if (invocation.getMethodSelect() instanceof final MemberSelectTree ms) {
      final TreePath receiverPath = new TreePath(invocationPath, ms.getExpression());
      return snapshot.trees().getTypeMirror(receiverPath);
    }

    for (TreePath current = invocationPath; current != null; current = current.getParentPath()) {
      if (current.getLeaf() instanceof ClassTree) {
        final Element classEl = snapshot.trees().getElement(current);
        return classEl != null ? classEl.asType() : null;
      }
    }

    return null;
  }

  private static boolean isSubtypeOf(
      final TypeElement typeElement,
      final String superClassName,
      final AttributedFileAnalysis snapshot) {
    if (superClassName.equals(typeElement.getQualifiedName().toString())) {
      return true;
    }

    final TypeElement superElement = snapshot.elements().getTypeElement(superClassName);
    if (superElement == null) {
      return false;
    }

    final TypeMirror subtype = snapshot.types().erasure(typeElement.asType());
    final TypeMirror supertype = snapshot.types().erasure(superElement.asType());
    return snapshot.types().isSubtype(subtype, supertype);
  }

  private static TreePath resolveExpectedSourcePath(
      final TreePath invocationPath, final ResultShape resultShape) {
    if (resultShape == ResultShape.INVOCATION_ITSELF) {
      return invocationPath;
    }

    if (resultShape.methodName != null) {
      final TreePath selectPath = invocationPath.getParentPath();
      if (selectPath == null || !(selectPath.getLeaf() instanceof final MemberSelectTree select)) {
        return null;
      }

      final TreePath parentInvocationPath = selectPath.getParentPath();
      if (parentInvocationPath == null
          || !(parentInvocationPath.getLeaf()
              instanceof final MethodInvocationTree parentInvocation)) {
        return null;
      }

      if (select.getExpression() == invocationPath.getLeaf()
          && parentInvocation.getMethodSelect() == selectPath.getLeaf()
          && resultShape.methodName.equals(select.getIdentifier().toString())) {
        return parentInvocationPath;
      }
    }

    return null;
  }

  private static TypeMirror enclosingExpectedType(
      final TreePath expressionPath, final AttributedFileAnalysis snapshot) {
    TreePath previous = expressionPath;
    for (TreePath current = expressionPath.getParentPath();
        current != null;
        current = current.getParentPath()) {
      final Tree leaf = current.getLeaf();

      if (leaf instanceof final VariableTree variable
          && variable.getInitializer() == previous.getLeaf()) {
        final Element el = snapshot.trees().getElement(current);
        return el != null ? el.asType() : null;
      }

      if (leaf instanceof final AssignmentTree assignment
          && assignment.getExpression() == previous.getLeaf()) {
        final TreePath lhsPath = new TreePath(current, assignment.getVariable());
        return snapshot.trees().getTypeMirror(lhsPath);
      }

      if (leaf instanceof final ReturnTree ret && ret.getExpression() == previous.getLeaf()) {
        return findEnclosingReturnTargetType(current, snapshot);
      }

      previous = current;
    }

    return null;
  }

  private static TypeMirror findEnclosingReturnTargetType(
      final TreePath path, final AttributedFileAnalysis snapshot) {
    for (TreePath current = path.getParentPath();
        current != null;
        current = current.getParentPath()) {
      if (current.getLeaf() instanceof LambdaExpressionTree) {
        return resolve(current, snapshot).orElse(null);
      }

      if (current.getLeaf() instanceof MethodTree) {
        final Element el = snapshot.trees().getElement(current);
        return el instanceof final ExecutableElement exec ? exec.getReturnType() : null;
      }
    }

    return null;
  }

  private static TypeMirror applyProjection(
      final TypeMirror expectedType, final Projection projection) {
    if (!(expectedType instanceof final DeclaredType declared)) {
      return null;
    }

    final List<? extends TypeMirror> typeArgs = declared.getTypeArguments();
    if (typeArgs.isEmpty()) {
      return null;
    }

    return switch (projection) {
      case FIRST_TYPE_ARGUMENT -> {
        final TypeMirror arg = typeArgs.getFirst();
        yield completeType(arg) ? arg : null;
      }
      case MAP_VALUE_TYPE -> {
        if (typeArgs.size() >= 2) {
          final TypeMirror arg = typeArgs.get(1);
          yield completeType(arg) ? arg : null;
        }
        yield null;
      }
      case MAP_VALUE_FIRST_TYPE_ARGUMENT -> {
        if (typeArgs.size() >= 2 && typeArgs.get(1) instanceof final DeclaredType mapValueType) {
          final List<? extends TypeMirror> innerArgs = mapValueType.getTypeArguments();
          if (!innerArgs.isEmpty()) {
            final TypeMirror arg = innerArgs.getFirst();
            yield completeType(arg) ? arg : null;
          }
        }
        yield null;
      }
    };
  }

  private static boolean completeType(final TypeMirror type) {
    return type != null
        && type.getKind() != TypeKind.VOID
        && type.getKind() != TypeKind.NONE
        && type.getKind() != TypeKind.ERROR;
  }
}
