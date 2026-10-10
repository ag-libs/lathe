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

  private static final Path SPOTLESS_LIB = Path.of("target", "test-spotless", "spotless-lib.jar");

  private static URLClassLoader spotless;
  private static Class<?> googleStep;

  @BeforeAll
  static void loadSpotless() throws Exception {
    spotless =
        new URLClassLoader(
            new URL[] {SPOTLESS_LIB.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    googleStep = spotless.loadClass("com.diffplug.spotless.java.GoogleJavaFormatStep");
  }

  @AfterAll
  static void closeSpotless() throws Exception {
    spotless.close();
  }

  @Test
  void withDefaults_unconfiguredAndConfiguredStep_spotlessDefaultsFillOnlyGaps() throws Exception {
    final FormatterSpec bare = FormatterResolver.withDefaults(google("", Map.of()), googleStep);

    assertThat(bare.version()).matches("\\d+\\.\\d+(\\.\\d+)?");
    assertThat(bare.options())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                LatheFlags.FORMAT_GROUP_ARTIFACT,
                "com.google.googlejavaformat:google-java-format",
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
        FormatterResolver.withDefaults(google("1.28.0", configuredOptions), googleStep);

    assertThat(configured.version()).isEqualTo("1.28.0");
    assertThat(configured.options())
        .containsEntry(LatheFlags.FORMAT_GROUP_ARTIFACT, "com.example:custom-gjf")
        .containsEntry(LatheFlags.FORMAT_REORDER_IMPORTS, "true")
        .containsEntry(LatheFlags.FORMAT_JAVADOC, "true");
  }

  @Test
  void javacGrants_engines_onlyJavacFormattersNeedGrants() {
    assertThat(FormatterResolver.javacGrants(google("", Map.of())))
        .isEqualTo(LatheFlags.FORMATTER_JAVAC_GRANTS);
    assertThat(
            FormatterResolver.javacGrants(
                new FormatterSpec(LatheFlags.FORMATTER_COMMAND, List.of("cat"))))
        .isEmpty();
  }

  private static FormatterSpec google(final String version, final Map<String, String> options) {
    return new FormatterSpec(LatheFlags.FORMATTER_GOOGLE, List.of(), version, options, List.of());
  }
}
