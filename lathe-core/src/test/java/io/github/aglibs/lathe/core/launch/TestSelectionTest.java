package io.github.aglibs.lathe.core.launch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

final class TestSelectionTest {

  @Test
  void toRunnerArgs_method_returnsMethodSelector() {
    final var selection =
        new TestSelection(TestSelectionKind.METHOD, "com.example.HelloTest#greet");

    assertThat(selection.toRunnerArgs())
        .containsExactly("--select-method", "com.example.HelloTest#greet");
  }

  @Test
  void toRunnerArgs_module_returnsModuleSelector() {
    final var selection = new TestSelection(TestSelectionKind.MODULE, "com.example.app");

    assertThat(selection.toRunnerArgs()).containsExactly("--select-module", "com.example.app");
  }

  @Test
  void fromRunnerFlag_classFlag_returnsClassKind() {
    assertThat(TestSelectionKind.fromRunnerFlag("--select-class"))
        .isEqualTo(TestSelectionKind.CLASS);
  }

  @Test
  void fromRunnerFlag_unknownFlag_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> TestSelectionKind.fromRunnerFlag("--select-unknown"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unknown selector --select-unknown");
  }
}
