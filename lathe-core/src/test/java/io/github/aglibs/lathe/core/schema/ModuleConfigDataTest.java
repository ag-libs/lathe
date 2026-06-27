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
            List.of("--enable-preview"));
    assertThat(data.sourceTree()).isEqualTo("classes");
    assertThat(data.compilerArgs()).containsExactly("--enable-preview");
  }

  @Test
  void constructor_nullSourceTree_throws() {
    assertThatThrownBy(
            () ->
                new ModuleConfigData(
                    null, "/out", null, List.of(), List.of(), List.of(), List.of(), null, null,
                    false, false, null, null))
        .hasMessageContaining("sourceTree");
  }

  @Test
  void constructor_nullCompilerArgs_defaultsToEmpty() {
    final var data =
        new ModuleConfigData(
            "classes", "/out", null, List.of(), List.of(), List.of(), List.of(), null, null, false,
            false, null, null);
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
            false, null, mutable);
    mutable.add("-Abar");
    assertThat(data.compilerArgs()).containsExactly("-Afoo");
  }
}
