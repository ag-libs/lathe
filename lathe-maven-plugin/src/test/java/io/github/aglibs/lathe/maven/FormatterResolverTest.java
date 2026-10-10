package io.github.aglibs.lathe.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.schema.FormatterSpec;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// Reads defaults from a real spotless-lib (copied into target/ by the build), loaded in an isolated
// classloader as sync does, so a renamed Spotless default fails here rather than in a user's sync.
class FormatterResolverTest {

  private static final Path SPOTLESS = Path.of("target", "test-spotless");

  private static URLClassLoader spotless;
  private static Class<?> googleStep;
  private static Class<?> palantirStep;
  private static Class<?> eclipseStep;

  @BeforeAll
  static void loadSpotless() throws Exception {
    spotless =
        new URLClassLoader(
            new URL[] {
              SPOTLESS.resolve("spotless-lib.jar").toUri().toURL(),
              SPOTLESS.resolve("spotless-lib-extra.jar").toUri().toURL()
            },
            ClassLoader.getPlatformClassLoader());
    googleStep = spotless.loadClass("com.diffplug.spotless.java.GoogleJavaFormatStep");
    palantirStep = spotless.loadClass("com.diffplug.spotless.java.PalantirJavaFormatStep");
    eclipseStep = spotless.loadClass("com.diffplug.spotless.extra.java.EclipseJdtFormatterStep");
  }

  @AfterAll
  static void closeSpotless() throws Exception {
    spotless.close();
  }

  @Test
  void withDefaults_unconfiguredAndConfiguredStep_spotlessDefaultsFillOnlyGaps() throws Exception {
    final FormatterSpec bare =
        FormatterResolver.withDefaults(google("", Map.of()), googleStep, "unused:fallback");

    assertThat(bare.version()).matches("\\d+\\.\\d+(\\.\\d+)?");
    assertThat(bare.options())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                LatheFlags.FORMAT_GROUP_ARTIFACT,
                "com.google.googlejavaformat:google-java-format",
                LatheFlags.FORMAT_STYLE,
                "GOOGLE",
                LatheFlags.FORMAT_REFLOW_LONG_STRINGS,
                "false",
                LatheFlags.FORMAT_REORDER_IMPORTS,
                "false",
                LatheFlags.FORMAT_JAVADOC,
                "true"));

    final Map<String, String> configuredOptions =
        Map.of(
            LatheFlags.FORMAT_GROUP_ARTIFACT,
            "com.example:custom-gjf",
            LatheFlags.FORMAT_REORDER_IMPORTS,
            "true");
    final FormatterSpec configured =
        FormatterResolver.withDefaults(
            google("1.28.0", configuredOptions), googleStep, "unused:fallback");

    assertThat(configured.version()).isEqualTo("1.28.0");
    assertThat(configured.options())
        .containsEntry(LatheFlags.FORMAT_GROUP_ARTIFACT, "com.example:custom-gjf")
        .containsEntry(LatheFlags.FORMAT_REORDER_IMPORTS, "true")
        .containsEntry(LatheFlags.FORMAT_JAVADOC, "true");
  }

  // palantir's step has no defaultGroupArtifact() nor reflow/reorder options: the fallback
  // coordinates apply and the unsupported options stay unset.
  @Test
  void withDefaults_palantirStep_usesFallbackArtifactAndOnlySupportedOptions() throws Exception {
    final FormatterSpec palantir =
        FormatterResolver.withDefaults(
            new FormatterSpec(LatheFlags.FORMATTER_PALANTIR, List.of()),
            palantirStep,
            "com.palantir.javaformat:palantir-java-format");

    assertThat(palantir.version()).matches("\\d+\\.\\d+(\\.\\d+)?");
    assertThat(palantir.options())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                LatheFlags.FORMAT_GROUP_ARTIFACT,
                "com.palantir.javaformat:palantir-java-format",
                LatheFlags.FORMAT_STYLE,
                "PALANTIR",
                LatheFlags.FORMAT_JAVADOC,
                "false"));
  }

  // The Eclipse step has a release default and none of the GJF-family options; the Spotless
  // lockfile
  // for that release pins its jars.
  @Test
  void withDefaults_eclipseStep_defaultsReleaseThatSpotlessPinsByLockfile() throws Exception {
    final FormatterSpec eclipse =
        FormatterResolver.withDefaults(
            new FormatterSpec(LatheFlags.FORMATTER_ECLIPSE, List.of()),
            eclipseStep,
            "org.eclipse.jdt:org.eclipse.jdt.core");

    assertThat(eclipse.version()).matches("4\\.\\d+");
    assertThat(eclipse.options())
        .containsExactlyEntriesOf(
            Map.of(LatheFlags.FORMAT_GROUP_ARTIFACT, "org.eclipse.jdt:org.eclipse.jdt.core"));
    assertThat(
            spotless.getResource(
                "com/diffplug/spotless/extra/eclipse_jdt_formatter/v%s.lockfile"
                    .formatted(eclipse.version())))
        .isNotNull();
  }

  @Test
  void javacGrants_engines_onlyJavacFormattersNeedGrants() {
    assertThat(FormatterResolver.javacGrants(google("", Map.of())))
        .isEqualTo(LatheFlags.FORMATTER_JAVAC_GRANTS);
    assertThat(
            FormatterResolver.javacGrants(
                new FormatterSpec(LatheFlags.FORMATTER_PALANTIR, List.of())))
        .isEqualTo(LatheFlags.FORMATTER_JAVAC_GRANTS);
    assertThat(
            FormatterResolver.javacGrants(
                new FormatterSpec(LatheFlags.FORMATTER_ECLIPSE, List.of())))
        .isEmpty();
    assertThat(
            FormatterResolver.javacGrants(
                new FormatterSpec(LatheFlags.FORMATTER_COMMAND, List.of("cat"))))
        .isEmpty();
  }

  private static FormatterSpec google(final String version, final Map<String, String> options) {
    return new FormatterSpec(LatheFlags.FORMATTER_GOOGLE, List.of(), version, options, List.of());
  }
}
