package io.github.aglibs.lathe.core.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModuleConfigDataTest {

  @Test
  void constructor_validFields_noException() {
    final var data =
        new ModuleConfigData(
            "classes",
            "/out",
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "21",
            "UTF-8",
            false,
            false,
            null,
            List.of("--enable-preview"),
            AnalysisMode.MODULE_SYSTEM,
            true,
            null);
    assertThat(data.sourceTree()).isEqualTo("classes");
    assertThat(data.compilerArgs()).containsExactly("--enable-preview");
    assertThat(data.analysisMode()).isEqualTo(AnalysisMode.MODULE_SYSTEM);
    assertThat(data.externalOutput()).isTrue();
  }

  // The front-end says whether a tree is test code; a params file from before the flag existed
  // says nothing, and only its test-classes tree is.
  @Test
  void constructor_testCodeGivenOrAbsent_givenWinsAbsentFollowsTestClasses() {
    assertThat(config("classes-integrationTest", true).testCode()).isTrue();
    assertThat(config("classes-jmh", false).testCode()).isFalse();
    assertThat(config("test-classes", null).testCode()).isTrue();
    assertThat(config("classes", null).testCode()).isFalse();
    assertThat(config("classes-integrationTest", null).testCode()).isFalse();
  }

  private static ModuleConfigData config(final String tree, final Boolean testCode) {
    return new ModuleConfigData(
        tree, "/out", null, List.of(), List.of(), List.of(), List.of(), null, null, false, false,
        null, null, null, false, testCode);
  }

  @Test
  void constructor_nullAnalysisMode_defaultsToClasspath() {
    final var data =
        new ModuleConfigData(
            "classes", "/out", null, List.of(), List.of(), List.of(), List.of(), null, null, false,
            false, null, null, null, false, null);
    assertThat(data.analysisMode()).isEqualTo(AnalysisMode.CLASSPATH);
  }

  @Test
  void constructor_nullSourceTree_throws() {
    assertThatThrownBy(
            () ->
                new ModuleConfigData(
                    null, "/out", null, List.of(), List.of(), List.of(), List.of(), null, null,
                    false, false, null, null, null, false, null))
        .hasMessageContaining("sourceTree");
  }

  @Test
  void constructor_nullCompilerArgs_defaultsToEmpty() {
    final var data =
        new ModuleConfigData(
            "classes", "/out", null, List.of(), List.of(), List.of(), List.of(), null, null, false,
            false, null, null, null, false, null);
    assertThat(data.compilerArgs()).isEmpty();
    assertThat(data.encoding()).isEqualTo("UTF-8");
  }

  @Test
  void constructor_mutableCompilerArgs_defensivelyCopied() {
    final var mutable = new ArrayList<String>();
    mutable.add("-Afoo");
    final var data =
        new ModuleConfigData(
            "classes", "/out", null, List.of(), List.of(), List.of(), List.of(), null, null, false,
            false, null, mutable, null, false, null);
    mutable.add("-Abar");
    assertThat(data.compilerArgs()).containsExactly("-Afoo");
  }
}
