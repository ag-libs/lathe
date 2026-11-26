package io.github.aglibs.lathe.server.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.lathe.core.LatheLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Runs the real spring-javaformat the build copies into target/, as the server loads a project's.
class SpringFormatEngineTest {

  private static final String CONFIG_FILE = ".springjavaformatconfig";
  private static final String MANGLED =
      """
      package demo;

      final class Sample {
      int a( ) {return   1;}

      int b( ) {return   2;}
      }
      """;
  private static final String TABS =
      """
      package demo;

      final class Sample {

      \tint a() {
      \t\treturn 1;
      \t}

      \tint b() {
      \t\treturn 2;
      \t}

      }
      """;

  @TempDir private Path project;

  @Test
  void format_noConfigFile_formatsWithTheDefaultTabsInOnePass() throws Exception {
    final FormatEngine engine = FormatterFixtures.springJavaFormat(project);

    assertThat(engine.format(MANGLED, target())).isEqualTo(TABS);
    assertThat(engine.format(TABS, target())).isEqualTo(TABS);
  }

  @Test
  void format_crlfSource_keepsItsLineEndings() throws Exception {
    final FormatEngine engine = FormatterFixtures.springJavaFormat(project);
    final String crlf = TABS.replace("\n", "\r\n");

    assertThat(engine.format(MANGLED.replace("\n", "\r\n"), target())).isEqualTo(crlf);
    assertThat(engine.format(crlf, target())).isEqualTo(crlf);
  }

  // The configuration is read from the module's base directory upwards, as the build plugin reads
  // it from the project's, so one engine serves a module that indents with spaces and the root
  // module with the default tabs.
  @Test
  void format_spacesConfigInOneModule_onlyThatModuleIndentsWithSpaces() throws Exception {
    final FormatEngine engine = FormatterFixtures.springJavaFormat(project);
    final Path spaces = project.resolve("spaces");
    writeSpacesConfig(spaces);

    final String spaced =
        engine.format(
            MANGLED, new FormatTarget(spaces.resolve("src/main/java/Sample.java"), "spaces", null));

    assertThat(spaced).isEqualTo(TABS.replace("\t", "    "));
    assertThat(engine.format(MANGLED, target())).isEqualTo(TABS);
  }

  // A configuration below the module's base directory is one the build never reads.
  @Test
  void format_configUnderSourceDirectory_ignored() throws Exception {
    final FormatEngine engine = FormatterFixtures.springJavaFormat(project);
    final Path sources = project.resolve("src/main/java");
    writeSpacesConfig(sources);

    assertThat(engine.format(MANGLED, FormatterFixtures.target(sources.resolve("Sample.java"))))
        .isEqualTo(TABS);
  }

  // Without a workspace root, the search starts at the file's directory.
  @Test
  void format_noWorkspaceRoot_readsConfigFromTheFilesDirectory() throws Exception {
    final FormatEngine engine = FormatterFixtures.springJavaFormat(null);
    writeSpacesConfig(project);

    assertThat(engine.format(MANGLED, target())).isEqualTo(TABS.replace("\t", "    "));
  }

  @Test
  void formatRanges_oneOfTwoMangledMethods_formatsOnlyThatRange() throws Exception {
    final FormatEngine engine = FormatterFixtures.springJavaFormat(project);
    final String source =
        TABS.replace("\tint a() {\n\t\treturn 1;\n\t}", "\tint a( ) {return   1;}")
            .replace("\tint b() {\n\t\treturn 2;\n\t}", "\tint b( ) {return   2;}");
    final String line = "\tint b( ) {return   2;}\n";
    final int start = source.indexOf(line);

    final String formatted =
        engine.formatRanges(source, target(), List.of(new TextRange(start, start + line.length())));

    assertThat(formatted).isEqualTo(source.replace(line, "\tint b() {\n\t\treturn 2;\n\t}\n"));
  }

  // As spring-javaformat's own plugin, unparsable source is left as it is rather than failing.
  @Test
  void format_syntaxError_leavesSourceUnchanged() throws Exception {
    final FormatEngine engine = FormatterFixtures.springJavaFormat(project);

    assertThat(engine.format("class { broken", target())).isEqualTo("class { broken");
  }

  @Test
  void format_eclipseRuntimeMissingFromClasspath_reportsFormatterUnavailable() {
    final List<String> withoutRuntime =
        FormatterFixtures.springJavaFormatClasspath().stream()
            .filter(jar -> !jar.contains("eclipse-runtime"))
            .toList();
    final FormatEngine engine = new SpringFormatEngine("0.0.47", withoutRuntime, project);

    assertThatThrownBy(() -> engine.format(MANGLED, target()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unavailable")
        .hasMessageContaining(LatheLayout.SYNC_SCRIPT_COMMAND);
  }

  private static void writeSpacesConfig(final Path dir) throws Exception {
    Files.createDirectories(dir);
    Files.writeString(
        dir.resolve(CONFIG_FILE), "indentation-style=spaces\n", StandardCharsets.UTF_8);
  }

  private FormatTarget target() {
    return FormatterFixtures.target(project.resolve("Sample.java"));
  }
}
