package io.github.aglibs.lathe.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LatheSettingsPluginFunctionalTest {

  private static final String SETTINGS =
      """
      plugins { id("io.github.ag-libs.lathe") }
      rootProject.name = "app"
      """;

  // Prints how many times Lathe was applied to the project: an init script and a settings plugin
  // load it in different class loaders, so applying it twice would show 2.
  private static final String PROBE =
      """
      println("LATHE ${project.name} " + plugins.count { it.javaClass.simpleName == "LatheProjectPlugin" })
      """;

  @TempDir Path dir;

  @Test
  void apply_multiProjectBuild_appliesLatheToEveryProjectAndReusesTheCache() throws Exception {
    final var fixture =
        new GradleFixture(dir)
            .file("settings.gradle.kts", SETTINGS + "include(\"lib\")\n")
            .file("build.gradle.kts", PROBE)
            .file("lib/build.gradle.kts", PROBE);

    final var isolatedProjects = "-Dorg.gradle.unsafe.isolated-projects=true";
    final var first = fixture.runner("help", isolatedProjects).build();
    final var second = fixture.runner("help", isolatedProjects).build();

    assertThat(first.getOutput())
        .contains("LATHE app 1", "LATHE lib 1", "Configuration cache entry stored.");
    assertThat(second.getOutput()).contains("Configuration cache entry reused.");
  }

  @Test
  void apply_ciOrCommandLine_commandLineWins() throws Exception {
    final var fixture =
        new GradleFixture(dir)
            .file("settings.gradle.kts", SETTINGS)
            .file("build.gradle.kts", PROBE);

    assertThat(fixture.runner("help").withEnvironment(ci()).build().getOutput())
        .contains("LATHE app 0");
    assertThat(
            fixture
                .runner("help", "-Plathe.disabled=false")
                .withEnvironment(ci())
                .build()
                .getOutput())
        .contains("LATHE app 1");
    assertThat(fixture.runner("help", "-Plathe.disabled=true").build().getOutput())
        .contains("LATHE app 0");
  }

  // The configuration cache must notice the new .lathe/ and configure the build again.
  @Test
  void apply_gradlePropertiesOptOut_dotLatheOptsBackInButNotOverCommandLine() throws Exception {
    final var fixture =
        new GradleFixture(dir)
            .file("settings.gradle.kts", SETTINGS)
            .file("build.gradle.kts", PROBE)
            .file("gradle.properties", "lathe.disabled=true\n");

    final var optedOut = fixture.runner("help", "--configuration-cache").build();
    Files.createDirectory(dir.resolve(".lathe"));
    final var optedIn = fixture.runner("help", "--configuration-cache").build();
    final var killed =
        fixture.runner("help", "--configuration-cache", "-Plathe.disabled=true").build();

    assertThat(optedOut.getOutput()).contains("LATHE app 0");
    assertThat(optedIn.getOutput()).contains("LATHE app 1");
    assertThat(killed.getOutput()).contains("LATHE app 0");
  }

  @Test
  void apply_initScript_appliesOnceWithOrWithoutTheCommittedPlugin() throws Exception {
    final var fixture =
        new GradleFixture(dir)
            .latheInitScript("lathe.init.gradle.kts")
            .file("settings.gradle.kts", "rootProject.name = \"app\"\n")
            .file("build.gradle.kts", PROBE);

    final var personal = fixture.runner("help", "--init-script", "lathe.init.gradle.kts").build();
    fixture.file("settings.gradle.kts", SETTINGS);
    final var both = fixture.runner("help", "--init-script", "lathe.init.gradle.kts").build();

    assertThat(personal.getOutput()).contains("LATHE app 1");
    assertThat(both.getOutput()).contains("LATHE app 1");
  }

  private static Map<String, String> ci() {
    final var env = new HashMap<>(System.getenv());
    env.put("CI", "true");
    return Map.copyOf(env);
  }
}
