package io.github.aglibs.lathe.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.WorkspaceStyleData;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LatheFormatterFunctionalTest {

  private static final String SETTINGS =
      "%sinclude(\"app\")\n%s"
          .formatted(GradleFixture.latheSettings("root"), GradleFixture.latheRepositories());
  private static final String SPOTLESS_WARNING = "Spotless formats this build";

  @TempDir Path dir;

  // The formatter lathe-style.json declares is completed from the Spotless release Lathe pins and
  // resolved through Gradle, with the indent it implies and the javac access it needs, under a
  // reused configuration cache. Editing the declaration invalidates the cache: the next sync
  // resolves the new formatter.
  @Test
  void sync_declaredFormatter_resolvedAndReresolvedWhenTheDeclarationChanges() throws Exception {
    final var fixture =
        app()
            .file(
                LatheLayout.STYLE_SHARED_FILE,
                "{\"formatter\": {\"engine\": \"google\", \"version\": \"1.28.0\"}}");

    fixture.runner("latheSync", "--configuration-cache").build();
    final var reused = fixture.runner("latheSync", "--configuration-cache").build();

    assertThat(reused.getOutput()).contains("Reusing configuration cache");
    final WorkspaceStyleData google = style();
    assertThat(google.formatter().engine()).isEqualTo(LatheFlags.FORMATTER_GOOGLE);
    assertThat(google.formatter().version()).isEqualTo("1.28.0");
    assertThat(google.formatter().options()).containsEntry(LatheFlags.FORMAT_STYLE, "GOOGLE");
    // Guava publishes Gradle module metadata: its JVM runtime variant, not a variant mismatch the
    // lenient view would drop.
    assertThat(google.formatter().classpath())
        .anyMatch(jar -> jar.endsWith("google-java-format-1.28.0.jar"))
        .anyMatch(jar -> Path.of(jar).getFileName().toString().matches("guava-.*-jre\\.jar"));
    assertThat(google.indent().block()).isEqualTo(2);
    assertThat(dir.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.JVM_ARGS_FILE))
        .content()
        .contains(LatheFlags.FORMATTER_JAVAC_GRANTS.get(0));

    fixture.file(LatheLayout.STYLE_SHARED_FILE, "{\"formatter\": {\"engine\": \"aosp\"}}");
    final var changed = fixture.runner("latheSync", "--configuration-cache").build();

    assertThat(changed.getOutput()).doesNotContain("Reusing configuration cache");
    final WorkspaceStyleData aosp = style();
    assertThat(aosp.formatter().engine()).isEqualTo(LatheFlags.FORMATTER_AOSP);
    assertThat(aosp.formatter().version()).matches("\\d+\\.\\d+(\\.\\d+)?");
    assertThat(aosp.formatter().options()).containsEntry(LatheFlags.FORMAT_STYLE, "AOSP");
    assertThat(aosp.indent().block()).isEqualTo(4);
  }

  // A project applying Spotless, whose settings Lathe cannot read on Gradle, is asked to declare
  // its formatter. Declared without a version, the formatter takes the default of the Spotless
  // Lathe pins, which may not be the subproject's, and the sync says so. Declaring none silences
  // both and leaves no generated style.
  @Test
  void sync_spotlessApplied_warnsUntilAFormatterIsDeclaredWithItsVersion() throws Exception {
    final var fixture =
        app()
            .file("app/build.gradle", "plugins { id 'java'\n id 'com.diffplug.spotless' }\n")
            .file(
                "buildSrc/src/main/java/StandInSpotless.java",
                """
                import org.gradle.api.Plugin;
                import org.gradle.api.Project;

                public class StandInSpotless implements Plugin<Project> {
                  public void apply(Project project) {}
                }
                """)
            .file(
                "buildSrc/src/main/resources/META-INF/gradle-plugins/com.diffplug.spotless.properties",
                "implementation-class=StandInSpotless\n");

    final var undeclared = fixture.runner("latheSync").build();
    fixture.file(LatheLayout.STYLE_SHARED_FILE, "{\"formatter\": {\"engine\": \"aosp\"}}");
    final var unversioned = fixture.runner("latheSync").build();
    fixture.file(LatheLayout.STYLE_SHARED_FILE, "{\"formatter\": {\"engine\": \"none\"}}");
    final var declared = fixture.runner("latheSync").build();

    assertThat(undeclared.getOutput()).contains(SPOTLESS_WARNING);
    assertThat(unversioned.getOutput())
        .doesNotContain(SPOTLESS_WARNING)
        .contains("is the default of the Spotless Lathe pins");
    assertThat(declared.getOutput())
        .doesNotContain(SPOTLESS_WARNING)
        .doesNotContain("the Spotless Lathe pins");
    assertThat(dir.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.STYLE_FILE)).doesNotExist();
  }

  // A declaration that cannot be completed (an Eclipse profile that is not there) is reported by
  // every sync, a reused configuration cache's included, and formats nothing.
  @Test
  void sync_declarationNotCompleted_warnsOnEveryRun() throws Exception {
    final var fixture =
        app()
            .file(
                LatheLayout.STYLE_SHARED_FILE,
                "{\"formatter\": {\"engine\": \"eclipse\", \"options\": {\"file\": \"missing.xml\"}}}");

    final var first = fixture.runner("latheSync", "--configuration-cache").build();
    final var reused = fixture.runner("latheSync", "--configuration-cache").build();

    assertThat(reused.getOutput()).contains("Reusing configuration cache");
    assertThat(List.of(first.getOutput(), reused.getOutput()))
        .allMatch(output -> output.contains("formatter eclipse declared in lathe-style.json"));
    assertThat(dir.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.STYLE_FILE)).doesNotExist();
  }

  private WorkspaceStyleData style() throws Exception {
    return Json.read(
        dir.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.STYLE_FILE),
        WorkspaceStyleData.class);
  }

  private GradleFixture app() throws Exception {
    return new GradleFixture(dir)
        .file("settings.gradle", SETTINGS)
        .file("build.gradle", "")
        .file("app/build.gradle", GradleFixture.JAVA)
        .file(
            "app/src/main/java/com/example/app/App.java",
            "package com.example.app; class App {}\n");
  }
}
