package io.github.aglibs.lathe.install;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.schema.FormatterSpec;
import io.github.aglibs.lathe.core.schema.IndentSpec;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// Reads defaults from a real spotless-lib (copied into target/ by the build), loaded in an isolated
// classloader as sync does, so a renamed Spotless default fails here rather than in a user's sync.
class FormatterStepsTest {

  private static final Path SPOTLESS = Path.of("target", "test-spotless");

  private static URLClassLoader spotless;

  @BeforeAll
  static void loadSpotless() throws Exception {
    spotless =
        new URLClassLoader(
            new URL[] {
              SPOTLESS.resolve("spotless-lib.jar").toUri().toURL(),
              SPOTLESS.resolve("spotless-lib-extra.jar").toUri().toURL()
            },
            ClassLoader.getPlatformClassLoader());
  }

  @AfterAll
  static void closeSpotless() throws Exception {
    spotless.close();
  }

  @Test
  void withDefaults_unconfiguredAndConfiguredStep_spotlessDefaultsFillOnlyGaps() throws Exception {
    final FormatterSpec bare = FormatterSteps.withDefaults(google("", Map.of()), spotless);

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
        FormatterSteps.withDefaults(google("1.28.0", configuredOptions), spotless);

    assertThat(configured.version()).isEqualTo("1.28.0");
    assertThat(configured.options())
        .containsEntry(LatheFlags.FORMAT_GROUP_ARTIFACT, "com.example:custom-gjf")
        .containsEntry(LatheFlags.FORMAT_REORDER_IMPORTS, "true")
        .containsEntry(LatheFlags.FORMAT_JAVADOC, "true");
  }

  // A declared aosp engine is google-java-format in AOSP style, unless the declaration says
  // otherwise; the style decides the indentation.
  @Test
  void withDefaults_aospEngine_takesAospStyleAndItsIndent() throws Exception {
    final FormatterSpec aosp =
        FormatterSteps.withDefaults(
            new FormatterSpec(LatheFlags.FORMATTER_AOSP, List.of()), spotless);

    assertThat(aosp.options()).containsEntry(LatheFlags.FORMAT_STYLE, "AOSP");
    assertThat(FormatterSteps.indent(aosp)).isEqualTo(new IndentSpec("google", 4, 8));
  }

  // palantir's step has no defaultGroupArtifact() nor reflow/reorder options: the fallback
  // coordinates apply and the unsupported options stay unset.
  @Test
  void withDefaults_palantirStep_usesFallbackArtifactAndOnlySupportedOptions() throws Exception {
    final FormatterSpec palantir =
        FormatterSteps.withDefaults(
            new FormatterSpec(LatheFlags.FORMATTER_PALANTIR, List.of()), spotless);

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
  // lockfile for that release pins its jars, each taken alone.
  @Test
  void jars_eclipseAndGoogle_lockfileEntriesAloneOrTheFormatterWithItsDependencies()
      throws Exception {
    final FormatterSpec eclipse =
        FormatterSteps.withDefaults(
            new FormatterSpec(LatheFlags.FORMATTER_ECLIPSE, List.of()), spotless);

    assertThat(eclipse.version()).matches("4\\.\\d+");
    assertThat(eclipse.options())
        .containsExactlyEntriesOf(
            Map.of(LatheFlags.FORMAT_GROUP_ARTIFACT, "org.eclipse.jdt:org.eclipse.jdt.core"));
    final FormatterSteps.Jars eclipseJars = FormatterSteps.jars(eclipse, spotless);
    assertThat(eclipseJars.transitive()).isFalse();
    assertThat(eclipseJars.coordinates())
        .hasSizeGreaterThan(1)
        .anyMatch(coordinates -> coordinates.startsWith("org.eclipse.jdt:org.eclipse.jdt.core:"));

    final FormatterSteps.Jars googleJars =
        FormatterSteps.jars(
            FormatterSteps.withDefaults(google("1.28.0", Map.of()), spotless), spotless);
    assertThat(googleJars.transitive()).isTrue();
    assertThat(googleJars.coordinates())
        .containsExactly("com.google.googlejavaformat:google-java-format:1.28.0");
  }

  @Test
  void jars_eclipseReleaseWithoutLockfile_refused() {
    final var unknown =
        new FormatterSpec(LatheFlags.FORMATTER_ECLIPSE, List.of(), "1.0", Map.of(), List.of());

    assertThatThrownBy(() -> FormatterSteps.jars(unknown, spotless))
        .isInstanceOf(SyncException.class)
        .hasMessageContaining("no lockfile");
  }

  @Test
  void resolve_googleAndEclipse_formatterResolvedTransitivelyLockfileJarsAlone() throws Exception {
    final var resolver = new RecordingResolver();

    final FormatterSpec google =
        FormatterSteps.resolve(google("1.28.0", Map.of()), spotless, resolver);
    final FormatterSpec eclipse =
        FormatterSteps.resolve(
            new FormatterSpec(LatheFlags.FORMATTER_ECLIPSE, List.of()), spotless, resolver);

    assertThat(google.classpath())
        .containsExactly(
            Path.of("transitive", "com.google.googlejavaformat_google-java-format_1.28.0.jar")
                .toString());
    assertThat(eclipse.classpath()).hasSizeGreaterThan(1);
    assertThat(resolver.alone).hasSize(eclipse.classpath().size());
    assertThat(resolver.transitive)
        .containsExactly("com.google.googlejavaformat:google-java-format:1.28.0");
  }

  @Test
  void javacGrants_engines_onlyJavacFormattersNeedGrants() {
    assertThat(FormatterSteps.javacGrants(google("", Map.of())))
        .isEqualTo(LatheFlags.FORMATTER_JAVAC_GRANTS);
    assertThat(
            FormatterSteps.javacGrants(new FormatterSpec(LatheFlags.FORMATTER_PALANTIR, List.of())))
        .isEqualTo(LatheFlags.FORMATTER_JAVAC_GRANTS);
    assertThat(
            FormatterSteps.javacGrants(new FormatterSpec(LatheFlags.FORMATTER_ECLIPSE, List.of())))
        .isEmpty();
    assertThat(
            FormatterSteps.javacGrants(
                new FormatterSpec(LatheFlags.FORMATTER_COMMAND, List.of("cat"))))
        .isEmpty();
  }

  @Test
  void indent_engines_googleStyleByTwoOtherStylesByFourTheRestFromEditorconfig() {
    final var editorconfig = new IndentSpec("editorconfig", 0, 0);

    assertThat(FormatterSteps.indent(google("", Map.of())))
        .isEqualTo(new IndentSpec("google", 2, 4));
    assertThat(FormatterSteps.indent(google("", Map.of(LatheFlags.FORMAT_STYLE, "aosp"))))
        .isEqualTo(new IndentSpec("google", 4, 8));
    assertThat(FormatterSteps.indent(new FormatterSpec(LatheFlags.FORMATTER_PALANTIR, List.of())))
        .isEqualTo(new IndentSpec("google", 4, 8));
    assertThat(
            FormatterSteps.indent(
                new FormatterSpec(
                    LatheFlags.FORMATTER_PALANTIR,
                    List.of(),
                    "",
                    Map.of(LatheFlags.FORMAT_STYLE, "GOOGLE"),
                    List.of())))
        .isEqualTo(new IndentSpec("google", 2, 4));
    assertThat(FormatterSteps.indent(new FormatterSpec(LatheFlags.FORMATTER_ECLIPSE, List.of())))
        .isEqualTo(editorconfig);
    assertThat(
            FormatterSteps.indent(new FormatterSpec(LatheFlags.FORMATTER_COMMAND, List.of("cat"))))
        .isEqualTo(editorconfig);
  }

  @Test
  void declared_styleWithAndWithoutFormatter_returnsOnlyTheFormatter() {
    assertThat(
            FormatterSteps.declared(
                "{\"formatter\":{\"engine\":\"google\",\"version\":\"1.28.0\"}}"))
        .contains(google("1.28.0", Map.of()));
    assertThat(FormatterSteps.declared("{\"indent\":{\"profile\":\"google\"}}")).isEmpty();
    assertThatThrownBy(() -> FormatterSteps.declared("{\"formatter\":"))
        .isInstanceOf(SyncException.class)
        .hasMessageContaining("lathe-style.json");
  }

  @Test
  void pinnedSpotless_filteredVersion_namesBothLibrariesAtOneRelease() {
    assertThat(FormatterSteps.pinnedSpotless())
        .hasSize(2)
        .allMatch(
            coordinates ->
                coordinates.matches(
                    "com\\.diffplug\\.spotless:spotless-lib(-extra)?:\\d+\\.\\d+\\.\\d+"));
  }

  private static FormatterSpec google(final String version, final Map<String, String> options) {
    return new FormatterSpec(LatheFlags.FORMATTER_GOOGLE, List.of(), version, options, List.of());
  }

  // Answers each coordinate with a path naming how it was asked for; a jar name never holds the
  // coordinates' colons, which no Windows path may.
  private static final class RecordingResolver implements ArtifactResolver {

    private final List<String> alone = new ArrayList<>();
    private final List<String> transitive = new ArrayList<>();

    @Override
    public Path resolve(final String coordinates) {
      alone.add(coordinates);
      return Path.of("alone", jar(coordinates));
    }

    @Override
    public List<Path> resolveTransitive(final String coordinates) {
      transitive.add(coordinates);
      return List.of(Path.of("transitive", jar(coordinates)));
    }

    private static String jar(final String coordinates) {
      return "%s.jar".formatted(coordinates.replace(':', '_'));
    }
  }
}
