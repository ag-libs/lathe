package io.github.aglibs.lathe.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import io.github.aglibs.lathe.analysis.testing.SampleFixture;
import io.github.aglibs.lathe.analysis.testing.TempSourceCompiler;
import org.junit.jupiter.api.Test;

class TreePathsTest {

  private static final String SOURCE =
      """
      class Outer {
        int field = 1;
        void run() {
          int local = 2;
          Runnable r = () -> System.out.println(local);
        }
      }
      """;

  // The nearest tree of a kind is the path itself or an ancestor; a boundary stops the climb.
  @Test
  void nearest_kindAboveOrBeyondABoundary_foundOrNone() {
    final AttributedFileAnalysis analysis = compile();

    final TreePath inLambda = pathAt(analysis, "System.out.println(local)", "println");
    assertThat(TreePaths.enclosingMethod(inLambda).getLeaf())
        .isInstanceOfSatisfying(
            MethodTree.class, m -> assertThat(m.getName().toString()).isEqualTo("run"));
    assertThat(TreePaths.enclosingClass(inLambda).getLeaf()).isInstanceOf(ClassTree.class);
    assertThat(TreePaths.nearestEnclosingStatement(inLambda))
        .as("the lambda's expression body is a boundary before any statement")
        .isNull();

    final TreePath local = pathAt(analysis, "int local = 2", "local");
    assertThat(TreePaths.enclosingVariable(local).getLeaf()).isInstanceOf(VariableTree.class);
    final TreePath inMethod = pathAt(analysis, "Runnable r", "Runnable");
    assertThat(TreePaths.nearest(inMethod, VariableTree.class, TreePaths::isMemberBoundary))
        .isNotNull();
    final TreePath methodName = pathAt(analysis, "void run()", "run");
    assertThat(TreePaths.enclosingVariable(methodName))
        .as("a method body never climbs to a field")
        .isNull();
  }

  private static AttributedFileAnalysis compile() {
    return new TempSourceCompiler()
        .compile(TempSourceCompiler.TEST_URI, SOURCE, CompileMode.OPEN)
        .fileAnalysis();
  }

  private static TreePath pathAt(
      final AttributedFileAnalysis analysis, final String expression, final String token) {
    return SampleFixture.pathAt(analysis.trees(), analysis.tree(), expression, token);
  }
}
