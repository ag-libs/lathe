package io.github.aglibs.lathe.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.ParamStore;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependencySourceTest {

  @TempDir Path tmp;

  @Test
  void present_filtersPresentEntries() {
    final DependencySource present =
        DependencySource.present(
            "com.example:present:1", Path.of("/repo/present.jar"), Path.of("/cache/present"), null);
    final DependencySource missing =
        DependencySource.missing("com.example:missing:1", Path.of("/repo/missing.jar"));
    final DependencySource skipped = DependencySource.skipped("com.example:skipped:null");

    assertThat(DependencySource.present(List.of(present, missing, skipped)))
        .containsExactly(present);
  }

  @Test
  void writeTo_writesPresentFieldsOnlyWhenAvailable() throws Exception {
    final ParamStore store = new ParamStore();
    store.putIndexed(
        "dependencySource",
        List.of(
            DependencySource.present(
                "com.example:present:1",
                Path.of("/repo/present.jar"),
                Path.of("/cache/present"),
                null),
            DependencySource.missing("com.example:missing:1", Path.of("/repo/missing.jar"))));

    final Path file = tmp.resolve("workspace.properties");
    store.store(file);

    final ParamStore loaded = ParamStore.load(file);
    assertThat(loaded.get("dependencySource.0.gav")).isEqualTo("com.example:present:1");
    assertThat(loaded.get("dependencySource.0.jar")).isEqualTo("/repo/present.jar");
    assertThat(loaded.get("dependencySource.0.status")).isEqualTo("present");
    assertThat(loaded.get("dependencySource.0.dir")).isEqualTo("/cache/present");
    assertThat(loaded.get("dependencySource.1.gav")).isEqualTo("com.example:missing:1");
    assertThat(loaded.get("dependencySource.1.jar")).isEqualTo("/repo/missing.jar");
    assertThat(loaded.get("dependencySource.1.status")).isEqualTo("missing");
    assertThat(loaded.get("dependencySource.1.dir")).isNull();
  }
}
