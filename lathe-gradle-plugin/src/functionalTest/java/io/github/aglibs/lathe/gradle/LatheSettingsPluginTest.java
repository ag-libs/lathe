package io.github.aglibs.lathe.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LatheSettingsPluginTest {

  @TempDir Path dir;

  @Test
  void apply_inSettingsPlugins_storesConfigurationCache() throws Exception {
    final var result =
        new GradleFixture(dir)
            .file(
                "settings.gradle.kts",
                """
                plugins { id("io.github.ag-libs.lathe") }
                rootProject.name = "app"
                """)
            .file("build.gradle.kts", "tasks.register(\"hello\")\n")
            .runner("hello", "--configuration-cache")
            .build();

    assertThat(result.getOutput()).contains("Configuration cache entry stored.");
  }
}
