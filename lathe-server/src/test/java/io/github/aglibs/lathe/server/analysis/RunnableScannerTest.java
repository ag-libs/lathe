package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.run.RunTarget;
import io.github.aglibs.lathe.server.run.RunnableKind;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunnableScannerTest {

  private static final String URI = TempSourceCompiler.TEST_URI;
  private static final String MODULE_REL = "app";

  @Test
  void runnables_mainMethod_returnsMainTarget() {
    final String source =
        """
        package demo;

        class App {
          public static void main(String[] args) {
          }
        }
        """;

    final List<RunTarget> targets = scan(source);

    assertThat(targets)
        .extracting(RunTarget::kind)
        .containsExactly(RunnableKind.MAIN, RunnableKind.MAIN_CLASS);
    final RunTarget method = targets.get(0);
    final RunTarget mainClass = targets.get(1);
    assertThat(method.id()).isEqualTo("demo.App#main");
    assertThat(method.label()).isEqualTo("main");
    assertThat(mainClass.id()).isEqualTo("demo.App");
    assertThat(mainClass.parentId()).isEqualTo("demo");
    // The method target's parentId pairs with the class-level target's id, so a client can link
    // them; the class target ranges the class declaration line (above the method line).
    assertThat(method.parentId()).isEqualTo(mainClass.id());
    assertThat(mainClass.range().getStart().getLine())
        .isLessThan(method.range().getStart().getLine());
  }

  @Test
  void runnables_packagePrivateStaticMain_returnsMainTarget() {
    final String source =
        """
        package demo;

        class App {
          static void main(String[] args) {
          }
        }
        """;

    assertThat(scan(source))
        .extracting(RunTarget::kind)
        .containsExactly(RunnableKind.MAIN, RunnableKind.MAIN_CLASS);
  }

  @Test
  void runnables_instanceMainNoArgs_returnsMainTarget() {
    final String source =
        """
        package demo;

        class App {
          void main() {
          }
        }
        """;

    final var target = scan(source).getFirst();

    assertThat(target.kind()).isEqualTo(RunnableKind.MAIN);
    assertThat(target.id()).isEqualTo("demo.App#main");
  }

  @Test
  void runnables_nestedClassWithMain_mainClassParentIdIsOuter() {
    final String source =
        """
        package demo;

        class Outer {
          static class Inner {
            public static void main(String[] args) {
            }
          }
        }
        """;

    final List<RunTarget> targets = scan(source);

    assertThat(targets)
        .extracting(RunTarget::id)
        .containsExactly("demo.Outer$Inner#main", "demo.Outer$Inner");
    final RunTarget mainClass =
        targets.stream().filter(t -> t.kind() == RunnableKind.MAIN_CLASS).findFirst().orElseThrow();
    assertThat(mainClass.parentId()).isEqualTo("demo.Outer");
  }

  @Test
  void runnables_mainWithNonStringArrayParam_returnsNoTargets() {
    final String source =
        """
        package demo;

        class App {
          void main(int arg) {
          }
        }
        """;

    assertThat(scan(source)).isEmpty();
  }

  @Test
  void runnables_mainWithTwoParams_returnsNoTargets() {
    final String source =
        """
        package demo;

        class App {
          void main(String[] args, int extra) {
          }
        }
        """;

    assertThat(scan(source)).isEmpty();
  }

  @Test
  void runnables_testMethod_returnsMethodAndClassAndPackageTargets() {
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
    assertThat(targets.get(0).parentId()).isEqualTo(targets.get(1).id());
    assertThat(targets.get(1).parentId()).isEqualTo(targets.get(2).id());
    assertThat(targets.get(2).parentId()).isEmpty();
  }

  @Test
  void runnables_parameterizedTestWithGenericParams_erasesToFullyQualifiedTypes() {
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

    assertThat(method.id())
        .isEqualTo("demo.FooTest#bar_input_result(java.lang.String,java.util.List)");
  }

  @Test
  void runnables_primitiveAndArrayParams_qualifyCorrectly() {
    final String source =
        """
        package demo;

        import org.junit.jupiter.api.Test;

        class FooTest {
          @Test
          void bar_input_result(int count, String[] names) {
          }
        }
        """;

    final var method = scan(source).getFirst();

    assertThat(method.id()).isEqualTo("demo.FooTest#bar_input_result(int,java.lang.String[])");
  }

  @Test
  void runnables_nestedClassWithTest_usesBinaryNameSeparator() {
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
    final RunTarget innerClass =
        targets.stream().filter(t -> t.id().equals("demo.Outer$Inner")).findFirst().orElseThrow();
    assertThat(innerClass.parentId()).isEqualTo("demo.Outer");
  }

  @Test
  void runnables_outerClassAlsoHasTest_innerClassParentIdIsOuterNotPackage() {
    final String source =
        """
        package demo;

        import org.junit.jupiter.api.Test;

        class Outer {
          @Test
          void outer_condition_result() {
          }

          class Inner {
            @Test
            void inner_condition_result() {
            }
          }
        }
        """;

    final List<RunTarget> targets = scan(source);
    final RunTarget outerClass =
        targets.stream().filter(t -> t.id().equals("demo.Outer")).findFirst().orElseThrow();
    final RunTarget innerClass =
        targets.stream().filter(t -> t.id().equals("demo.Outer$Inner")).findFirst().orElseThrow();

    assertThat(innerClass.parentId()).isEqualTo(outerClass.id());
    assertThat(outerClass.parentId()).isEqualTo("demo");
  }

  @Test
  void runnables_classWithoutTestAnnotations_returnsNoTargets() {
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
    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(URI, source, 1, CompileMode.OPEN);
      return session.runnables(URI, source, 1, MODULE_REL);
    }
  }
}
