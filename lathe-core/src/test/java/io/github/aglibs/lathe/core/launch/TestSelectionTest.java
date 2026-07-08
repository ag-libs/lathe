package io.github.aglibs.lathe.core.launch;

import static org.assertj.core.api.Assertions.assertThat;

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
}
