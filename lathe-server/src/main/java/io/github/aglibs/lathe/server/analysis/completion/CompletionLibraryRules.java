package io.github.aglibs.lathe.server.analysis.completion;

import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

final class CompletionLibraryRules {

  enum LambdaResultShape {
    INVOCATION_ITSELF(null),
    RECEIVER_OF_TO_LIST("toList"),
    RECEIVER_OF_COLLECT("collect");

    private final String methodName;

    LambdaResultShape(final String methodName) {
      this.methodName = methodName;
    }

    String methodName() {
      return methodName;
    }
  }

  enum TypeProjection {
    FIRST_TYPE_ARGUMENT,
    MAP_VALUE_TYPE,
    MAP_VALUE_FIRST_TYPE_ARGUMENT
  }

  record LambdaRule(
      String ownerQualifiedName,
      String methodName,
      int lambdaArgumentIndex,
      LambdaResultShape resultShape,
      TypeProjection projection) {

    LambdaRule {
      ValidCheck.check()
          .notBlank(ownerQualifiedName, "ownerQualifiedName")
          .notBlank(methodName, "methodName")
          .isNonNegative(lambdaArgumentIndex, "lambdaArgumentIndex")
          .notNull(resultShape, "resultShape")
          .notNull(projection, "projection")
          .validate();
    }
  }

  record StaticMemberResultRule(
      String receiverSimpleName,
      String receiverQualifiedName,
      String enclosingOwnerQualifiedName,
      String enclosingMethodName,
      int enclosingArgumentIndex,
      String candidateResultQualifiedName,
      int candidateResultTypeArgumentIndex) {

    StaticMemberResultRule {
      ValidCheck.check()
          .notBlank(receiverSimpleName, "receiverSimpleName")
          .notBlank(receiverQualifiedName, "receiverQualifiedName")
          .notBlank(enclosingOwnerQualifiedName, "enclosingOwnerQualifiedName")
          .notBlank(enclosingMethodName, "enclosingMethodName")
          .isNonNegative(enclosingArgumentIndex, "enclosingArgumentIndex")
          .notBlank(candidateResultQualifiedName, "candidateResultQualifiedName")
          .isNonNegative(candidateResultTypeArgumentIndex, "candidateResultTypeArgumentIndex")
          .validate();
    }

    boolean matchesReceiver(final String receiverText) {
      return receiverSimpleName.equals(receiverText) || receiverQualifiedName.equals(receiverText);
    }

    boolean matchesEnclosingInvocation(final TreePath path, final AttributedFileAnalysis snapshot) {
      if (!(path.getLeaf() instanceof final MethodInvocationTree invocation)
          || !enclosingMethodName.equals(methodSelectName(invocation.getMethodSelect()))) {
        return false;
      }

      final Element element = snapshot.trees().getElement(path);
      if (element == null) {
        return true;
      }

      return element instanceof final ExecutableElement method
          && method.getEnclosingElement() instanceof final TypeElement owner
          && enclosingOwnerQualifiedName.equals(owner.getQualifiedName().toString());
    }

    TypeMirror candidateResultType(final TypeMirror candidateType) {
      if (!(candidateType instanceof final DeclaredType declaredType)
          || declaredType.getTypeArguments().size() <= candidateResultTypeArgumentIndex
          || !(declaredType.asElement() instanceof final TypeElement typeElement)
          || !candidateResultQualifiedName.equals(typeElement.getQualifiedName().toString())) {
        return null;
      }

      return declaredType.getTypeArguments().get(candidateResultTypeArgumentIndex);
    }
  }

  record StaticMemberResultContext(StaticMemberResultRule rule, TypeMirror expectedResultType) {

    StaticMemberResultContext {
      ValidCheck.check()
          .notNull(rule, "rule")
          .notNull(expectedResultType, "expectedResultType")
          .validate();
    }

    boolean matches(final TypeMirror candidateType, final AttributedFileAnalysis analysis) {
      final TypeMirror resultType = rule.candidateResultType(candidateType);
      return resultType != null
          && (analysis.types().isAssignable(resultType, expectedResultType)
              || analysis
                  .types()
                  .isAssignable(
                      analysis.types().erasure(resultType),
                      analysis.types().erasure(expectedResultType)));
    }
  }

  private static final List<LambdaRule> LAMBDA_RULES =
      List.of(
          new LambdaRule(
              "java.util.Optional",
              "map",
              0,
              LambdaResultShape.INVOCATION_ITSELF,
              TypeProjection.FIRST_TYPE_ARGUMENT),
          new LambdaRule(
              "java.util.Optional",
              "flatMap",
              0,
              LambdaResultShape.INVOCATION_ITSELF,
              TypeProjection.FIRST_TYPE_ARGUMENT),
          new LambdaRule(
              "java.util.stream.Stream",
              "map",
              0,
              LambdaResultShape.RECEIVER_OF_TO_LIST,
              TypeProjection.FIRST_TYPE_ARGUMENT),
          new LambdaRule(
              "java.util.stream.Stream",
              "flatMap",
              0,
              LambdaResultShape.RECEIVER_OF_TO_LIST,
              TypeProjection.FIRST_TYPE_ARGUMENT),
          new LambdaRule(
              "java.util.stream.Stream",
              "mapToObj",
              0,
              LambdaResultShape.RECEIVER_OF_TO_LIST,
              TypeProjection.FIRST_TYPE_ARGUMENT),
          new LambdaRule(
              "java.util.stream.Stream",
              "map",
              0,
              LambdaResultShape.RECEIVER_OF_COLLECT,
              TypeProjection.FIRST_TYPE_ARGUMENT),
          new LambdaRule(
              "java.util.stream.Stream",
              "map",
              0,
              LambdaResultShape.RECEIVER_OF_COLLECT,
              TypeProjection.MAP_VALUE_TYPE),
          new LambdaRule(
              "java.util.stream.Stream",
              "map",
              0,
              LambdaResultShape.RECEIVER_OF_COLLECT,
              TypeProjection.MAP_VALUE_FIRST_TYPE_ARGUMENT),
          new LambdaRule(
              "java.util.concurrent.CompletionStage",
              "thenApply",
              0,
              LambdaResultShape.INVOCATION_ITSELF,
              TypeProjection.FIRST_TYPE_ARGUMENT),
          new LambdaRule(
              "java.util.concurrent.CompletionStage",
              "thenApplyAsync",
              0,
              LambdaResultShape.INVOCATION_ITSELF,
              TypeProjection.FIRST_TYPE_ARGUMENT),
          new LambdaRule(
              "java.util.concurrent.CompletionStage",
              "thenCompose",
              0,
              LambdaResultShape.INVOCATION_ITSELF,
              TypeProjection.FIRST_TYPE_ARGUMENT),
          new LambdaRule(
              "java.util.concurrent.CompletionStage",
              "thenComposeAsync",
              0,
              LambdaResultShape.INVOCATION_ITSELF,
              TypeProjection.FIRST_TYPE_ARGUMENT),
          new LambdaRule(
              "java.util.concurrent.CompletionStage",
              "handle",
              0,
              LambdaResultShape.INVOCATION_ITSELF,
              TypeProjection.FIRST_TYPE_ARGUMENT),
          new LambdaRule(
              "java.util.concurrent.CompletionStage",
              "handleAsync",
              0,
              LambdaResultShape.INVOCATION_ITSELF,
              TypeProjection.FIRST_TYPE_ARGUMENT),
          new LambdaRule(
              "java.util.concurrent.CompletionStage",
              "exceptionally",
              0,
              LambdaResultShape.INVOCATION_ITSELF,
              TypeProjection.FIRST_TYPE_ARGUMENT));

  private static final List<StaticMemberResultRule> STATIC_MEMBER_RESULT_RULES =
      List.of(
          new StaticMemberResultRule(
              "Collectors",
              "java.util.stream.Collectors",
              "java.util.stream.Stream",
              "collect",
              0,
              "java.util.stream.Collector",
              2));

  private CompletionLibraryRules() {}

  static List<LambdaRule> lambdaRules() {
    return LAMBDA_RULES;
  }

  static Optional<StaticMemberResultRule> staticMemberResultRule(final String receiverText) {
    return STATIC_MEMBER_RESULT_RULES.stream()
        .filter(rule -> rule.matchesReceiver(receiverText))
        .findFirst();
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
}
