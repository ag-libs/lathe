package io.github.aglibs.lathe.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DependencySourceTest {

  @Test
  void present_filtersPresentEntries() {
    final DependencySource present =
        DependencySource.present(
            "com.example:present:1",
            Path.of("/repo/present.jar"),
            Path.of("/cache/present"),
            null,
            List.of());
    final DependencySource missing =
        DependencySource.missing("com.example:missing:1", Path.of("/repo/missing.jar"), List.of());

    assertThat(DependencySource.present(List.of(present, missing))).containsExactly(present);
  }

  @Test
  void toEntry_presentSource_mapsAllFields() {
    final var source =
        DependencySource.present(
            "com.example:lib:1",
            Path.of("/repo/lib.jar"),
            Path.of("/cache/lib"),
            null,
            List.of(Path.of("/repo/dep-a.jar"), Path.of("/repo/dep-b.jar")));

    final var entry = source.toEntry();

    assertThat(entry.gav()).isEqualTo("com.example:lib:1");
    assertThat(entry.jar()).isEqualTo("/repo/lib.jar");
    assertThat(entry.status()).isEqualTo("present");
    assertThat(entry.dir()).isEqualTo("/cache/lib");
    assertThat(entry.classpath()).containsExactly("/repo/dep-a.jar", "/repo/dep-b.jar");
  }

  @Test
  void toEntry_missingSource_hasNullDirAndEmptyClasspath() {
    final var source =
        DependencySource.missing("com.example:absent:1", Path.of("/repo/absent.jar"), List.of());

    final var entry = source.toEntry();

    assertThat(entry.gav()).isEqualTo("com.example:absent:1");
    assertThat(entry.jar()).isEqualTo("/repo/absent.jar");
    assertThat(entry.status()).isEqualTo("missing");
    assertThat(entry.dir()).isNull();
    assertThat(entry.classpath()).isEmpty();
  }
}
