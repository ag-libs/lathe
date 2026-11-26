package io.github.aglibs.lathe.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.WorkspaceStyleData;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LatheFormatterFunctionalTest {

  private static final String SETTINGS =
      "%sinclude(\"app\")\n%s"
          .formatted(GradleFixture.latheSettings("root"), GradleFixture.latheRepositories());
  private static final String SPOTLESS_WARNING = "Spotless formats this build";
  // The release Lathe pins, which the build puts in the Lathe repository, so no fixture fetches it
  // from Central; the absent fallback warning tells the plugin's release from the pinned one.
  private static final String SPRING_RELEASE =
      Objects.requireNonNull(
          System.getProperty("lathe.springJavaformatVersion"),
          "lathe.springJavaformatVersion is not set; run the fixtures through the Maven build");

  @TempDir Path dir;

  // The formatter lathe-style.json declares is completed from the Spotless release Lathe pins and
  // resolved through Gradle, with the indent it implies and the javac access it needs, under a
  // reused configuration cache. Editing the declaration invalidates the cache: the next sync
  // resolves the new formatter. Both name google-java-format 1.28.0, which the build puts in the
  // Lathe repository; a version left out is FormatterStepsTest's.
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

    fixture.file(
        LatheLayout.STYLE_SHARED_FILE,
        "{\"formatter\": {\"engine\": \"aosp\", \"version\": \"1.28.0\"}}");
    final var changed = fixture.runner("latheSync", "--configuration-cache").build();

    assertThat(changed.getOutput()).doesNotContain("Reusing configuration cache");
    final WorkspaceStyleData aosp = style();
    assertThat(aosp.formatter().engine()).isEqualTo(LatheFlags.FORMATTER_AOSP);
    assertThat(aosp.formatter().version()).isEqualTo("1.28.0");
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
        withStandInSpotless(app())
            .file("app/build.gradle", "plugins { id 'java'\n id 'com.diffplug.spotless' }\n");

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

  // A root without Java that applies Spotless to the whole build's sources is noticed too.
  @Test
  void sync_spotlessAppliedInARootWithoutJava_warns() throws Exception {
    final var fixture =
        withStandInSpotless(app()).file("build.gradle", "plugins { id 'com.diffplug.spotless' }\n");

    assertThat(fixture.runner("latheSync").build().getOutput()).contains(SPOTLESS_WARNING);
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

  // The root's spring-javaformat plugin is detected at its release, under a reused configuration
  // cache: the formatter with its Eclipse runtime, the indent left to .editorconfig, no javac
  // grants. A spring declaration without a version takes the plugin's release too.
  @Test
  void sync_rootAppliesSpringJavaFormat_springEngineAtThePluginRelease() throws Exception {
    final var fixture =
        withStandInSpring(app()).file("build.gradle", "plugins { id 'io.spring.javaformat' }\n");

    final var first = fixture.runner("latheSync", "--configuration-cache").build();
    final var reused = fixture.runner("latheSync", "--configuration-cache").build();
    final WorkspaceStyleData detected = style();
    fixture.file(LatheLayout.STYLE_SHARED_FILE, "{\"formatter\": {\"engine\": \"spring\"}}");
    fixture.runner("latheSync").build();

    assertThat(reused.getOutput()).contains("Reusing configuration cache");
    assertThat(first.getOutput()).doesNotContain("records no release");
    assertThat(detected.formatter().engine()).isEqualTo(LatheFlags.FORMATTER_SPRING);
    assertThat(detected.formatter().version()).isEqualTo(SPRING_RELEASE);
    assertThat(detected.formatter().classpath())
        .anyMatch(
            jar -> jar.endsWith("spring-javaformat-formatter-%s.jar".formatted(SPRING_RELEASE)))
        .anyMatch(
            jar ->
                jar.endsWith(
                    "spring-javaformat-formatter-eclipse-runtime-%s.jar"
                        .formatted(SPRING_RELEASE)));
    assertThat(detected.indent().profile()).isEqualTo("editorconfig");
    assertThat(dir.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.JVM_ARGS_FILE))
        .doesNotExist();
    assertThat(style().formatter().version()).isEqualTo(SPRING_RELEASE);
  }

  // Spotless anywhere in the build wins over the root's spring-javaformat, as on Maven: Lathe
  // cannot read Spotless's settings on Gradle, so it formats nothing and asks for a declaration.
  @Test
  void sync_springJavaFormatBesideSpotless_formatsNothingAndWarns() throws Exception {
    final var fixture =
        withStandInSpotless(withStandInSpring(app()))
            .file("build.gradle", "plugins { id 'io.spring.javaformat' }\n")
            .file("app/build.gradle", "plugins { id 'java'\n id 'com.diffplug.spotless' }\n");

    final var result = fixture.runner("latheSync").build();

    assertThat(result.getOutput()).contains(SPOTLESS_WARNING);
    assertThat(dir.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.STYLE_FILE)).doesNotExist();
  }

  // Only the root's plugin is read: a subproject's is out of its reach, and the build declares
  // the engine instead. No style, no configurations of Lathe's, and nothing to warn about.
  @Test
  void sync_springJavaFormatOnlyInASubproject_notDetected() throws Exception {
    final var fixture =
        withStandInSpring(app())
            .file("app/build.gradle", "plugins { id 'java'\n id 'io.spring.javaformat' }\n");

    final var result = fixture.runner("latheSync", "dependencies").build();

    assertThat(dir.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.STYLE_FILE)).doesNotExist();
    assertThat(result.getOutput())
        .doesNotContain("latheFormatter")
        .doesNotContain("[lathe] formatter");
  }

  // A plugin jar that records no release runs the release Lathe pins, and the sync says so.
  @Test
  void sync_springJavaFormatWithoutRecordedRelease_pinnedReleaseWithAWarning() throws Exception {
    final var fixture =
        withStandInSpring(app(), "")
            .file("build.gradle", "plugins { id 'io.spring.javaformat' }\n");

    final var result = fixture.runner("latheSync").build();

    assertThat(result.getOutput()).contains("records no release");
    assertThat(style().formatter().version()).matches("\\d+\\.\\d+\\.\\d+");
  }

  private static GradleFixture withStandInSpring(final GradleFixture fixture) throws Exception {
    return withStandInSpring(fixture, SPRING_RELEASE);
  }

  // A plugin under spring-javaformat's id whose jar records release, as the real plugin's does
  // (none when empty); the formatter jars come from the Lathe repository the build fills.
  private static GradleFixture withStandInSpring(final GradleFixture fixture, final String release)
      throws Exception {
    final String manifest =
        release.isEmpty()
            ? ""
            : "jar { manifest { attributes('Implementation-Version': '%s') } }\n"
                .formatted(release);
    return fixture
        .file(
            "buildSrc/build.gradle",
            """
                plugins { id 'java' }
                dependencies { implementation gradleApi() }
                %s"""
                .formatted(manifest))
        .file(
            "buildSrc/src/main/java/standin/StandInSpring.java",
            """
                package standin;

                import org.gradle.api.Plugin;
                import org.gradle.api.Project;

                public class StandInSpring implements Plugin<Project> {
                  public void apply(Project project) {}
                }
                """)
        .file(
            "buildSrc/src/main/resources/META-INF/gradle-plugins/io.spring.javaformat.properties",
            "implementation-class=standin.StandInSpring\n");
  }

  // A plugin under Spotless's id, so the fixture needs no plugin portal: the sync only asks
  // whether a project applies it.
  private static GradleFixture withStandInSpotless(final GradleFixture fixture) throws Exception {
    return fixture
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
