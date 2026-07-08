package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.run.RunTarget;
import io.github.aglibs.lathe.server.run.RunnableKind;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunnableScannerTest {

  @Test
  void scan_mainMethod_returnsMainTarget() {
    final String source =
        """
        package demo;

        class App {
          public static void main(String[] args) {
          }
        }
        """;

    final List<RunTarget> targets = scan(source);

    assertThat(targets).hasSize(1);
    final var target = targets.getFirst();
    assertThat(target.kind()).isEqualTo(RunnableKind.MAIN);
    assertThat(target.id()).isEqualTo("demo.App#main");
    assertThat(target.label()).isEqualTo("main");
  }

  @Test
  void scan_nonPublicMain_returnsNoTargets() {
    final String source =
        """
        package demo;

        class App {
          static void main(String[] args) {
          }
        }
        """;

    assertThat(scan(source)).isEmpty();
  }

  @Test
  void scan_testMethod_returnsMethodAndClassAndPackageTargets() {
    final String source =
        """
        package demo;

        import org.junit.jupiter.api.Test;

        class FooTest {
          @Test
          void bar_condition_result() {
          }
        }
        """;

    final List<RunTarget> targets = scan(source);

    assertThat(targets)
        .extracting(RunTarget::kind)
        .containsExactly(
            RunnableKind.TEST_METHOD, RunnableKind.TEST_CLASS, RunnableKind.TEST_PACKAGE);
    assertThat(targets.get(0).id()).isEqualTo("demo.FooTest#bar_condition_result()");
    assertThat(targets.get(1).id()).isEqualTo("demo.FooTest");
    assertThat(targets.get(2).id()).isEqualTo("demo");
  }

  @Test
  void scan_parameterizedTestWithParams_erasesGenericParamTypes() {
    final String source =
        """
        package demo;

        import java.util.List;
        import org.junit.jupiter.params.ParameterizedTest;

        class FooTest {
          @ParameterizedTest
          void bar_input_result(String name, List<String> values) {
          }
        }
        """;

    final var method = scan(source).getFirst();

    assertThat(method.id()).isEqualTo("demo.FooTest#bar_input_result(String,List)");
  }

  @Test
  void scan_nestedClassWithTest_usesDollarSeparatedId() {
    final String source =
        """
        package demo;

        import org.junit.jupiter.api.Test;

        class Outer {
          class Inner {
            @Test
            void nested_condition_result() {
            }
          }
        }
        """;

    final List<RunTarget> targets = scan(source);

    assertThat(targets)
        .extracting(RunTarget::id)
        .contains("demo.Outer$Inner#nested_condition_result()", "demo.Outer$Inner");
  }

  @Test
  void scan_classWithoutTestAnnotations_returnsNoTargets() {
    final String source =
        """
        package demo;

        class Plain {
          void method() {
          }
        }
        """;

    assertThat(scan(source)).isEmpty();
  }

  private static List<RunTarget> scan(final String source) {
    try (var parser = new SourceParser()) {
      return parser
          .parseContent(
              "file:///Test.java",
              source,
              (trees, tree) -> RunnableScanner.scan(trees, tree, "file:///Test.java", "app"))
          .orElseThrow();
    }
  }
}
