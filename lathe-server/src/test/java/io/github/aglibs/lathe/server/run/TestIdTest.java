package io.github.aglibs.lathe.server.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.validcheck.ValidationException;
import org.junit.jupiter.api.Test;

final class TestIdTest {

  @Test
  void positionId_zeroArgMethod_hasEmptyParens() {
    assertThat(TestId.positionId("pkg.FooTest", "bar", "")).isEqualTo("pkg.FooTest#bar()");
  }

  @Test
  void positionId_junitCommaSpaceParams_stripsWhitespaceToJavacForm() {
    assertThat(TestId.positionId("pkg.FooTest", "bar", "java.lang.String, int"))
        .isEqualTo("pkg.FooTest#bar(java.lang.String,int)");
  }

  @Test
  void positionId_nestedClass_keepsBinaryDollarName() {
    assertThat(TestId.positionId("pkg.Outer$Inner", "t", "")).isEqualTo("pkg.Outer$Inner#t()");
  }

  // JUnit names parameter types as Class.getName does; javac's erasure prints source form.
  @Test
  void positionId_jvmFormParameterTypes_matchJavacSourceForm() {
    assertThat(
            TestId.positionId(
                "pkg.Outer$Inner",
                "t",
                "[I, [[J, [Ljava.lang.String;, pkg.Outer$Shape, [Lpkg.Outer$Shape;, boolean"))
        .isEqualTo(
            "pkg.Outer$Inner#t(int[],long[][],java.lang.String[],pkg.Outer.Shape,"
                + "pkg.Outer.Shape[],boolean)");
  }

  @Test
  void positionId_blankClassName_isRejected() {
    assertThatThrownBy(() -> TestId.positionId("", "bar", ""))
        .isInstanceOf(ValidationException.class);
  }
}
