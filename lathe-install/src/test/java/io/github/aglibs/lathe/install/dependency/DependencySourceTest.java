package io.github.aglibs.lathe.install.dependency;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.schema.SourceStatus;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DependencySourceTest {

  @Test
  void withSources_filtersPresentEntries() {
    final DependencySource present =
        DependencySource.present(
            "com.example:present:1",
            Path.of("/repo/present.jar"),
            Path.of("/cache/present"),
            null,
            List.of());
    final DependencySource missing =
        DependencySource.missing("com.example:missing:1", Path.of("/repo/missing.jar"), List.of());

    assertThat(DependencySource.withSources(List.of(present, missing))).containsExactly(present);
  }

  @Test
  void toData_presentSource_mapsAllFields() {
    final var source =
        DependencySource.present(
            "com.example:lib:1",
            Path.of("/repo/lib.jar"),
            Path.of("/cache/lib"),
            null,
            List.of(Path.of("/repo/dep-a.jar"), Path.of("/repo/dep-b.jar")));

    final var entry = source.toData();

    assertThat(entry.gav()).isEqualTo("com.example:lib:1");
    assertThat(entry.jar()).isEqualTo(Path.of("/repo/lib.jar").toString());
    assertThat(entry.status()).isEqualTo(SourceStatus.PRESENT);
    assertThat(entry.dir()).isEqualTo(Path.of("/cache/lib").toString());
    assertThat(entry.classpath())
        .containsExactly(
            Path.of("/repo/dep-a.jar").toString(), Path.of("/repo/dep-b.jar").toString());
  }

  @Test
  void toData_missingSource_hasNullDirAndEmptyClasspath() {
    final var source =
        DependencySource.missing("com.example:absent:1", Path.of("/repo/absent.jar"), List.of());

    final var entry = source.toData();

    assertThat(entry.gav()).isEqualTo("com.example:absent:1");
    assertThat(entry.jar()).isEqualTo(Path.of("/repo/absent.jar").toString());
    assertThat(entry.status()).isEqualTo(SourceStatus.MISSING);
    assertThat(entry.dir()).isNull();
    assertThat(entry.classpath()).isEmpty();
  }
}
