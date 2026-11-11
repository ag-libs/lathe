package io.github.aglibs.lathe.server.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.FormatterSpec;
import io.github.aglibs.lathe.core.schema.IndentSpec;
import io.github.aglibs.lathe.core.schema.WorkspaceStyleData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceStyleTest {

  @TempDir private Path root;

  @Test
  void read_noFiles_returnsNull() throws IOException {
    assertThat(WorkspaceStyle.read(root)).isNull();
  }

  @Test
  void read_generatedOnly_readsGenerated() throws IOException {
    writeGenerated(new FormatterSpec("google", List.of()), new IndentSpec("google", 2, 4));

    final WorkspaceStyleData style = WorkspaceStyle.read(root);

    assertThat(style.formatter().engine()).isEqualTo("google");
    assertThat(style.indent().block()).isEqualTo(2);
  }

  @Test
  void read_committedAndGenerated_committedWins() throws IOException {
    writeGenerated(new FormatterSpec("google", List.of()), new IndentSpec("google", 2, 4));
    Json.write(
        new WorkspaceStyleData(new FormatterSpec("none", List.of()), null),
        root.resolve(LatheLayout.STYLE_SHARED_FILE));

    assertThat(WorkspaceStyle.read(root).formatter().engine()).isEqualTo("none");
  }

  // A committed file that sets only the indent keeps the formatter sync generated, and vice versa.
  @Test
  void read_committedSetsOneSection_otherSectionComesFromGenerated() throws IOException {
    writeGenerated(new FormatterSpec("google", List.of()), new IndentSpec("google", 2, 4));
    final Path committed = root.resolve(LatheLayout.STYLE_SHARED_FILE);

    Json.write(new WorkspaceStyleData(null, new IndentSpec("google", 4, 8)), committed);
    final WorkspaceStyleData indentOnly = WorkspaceStyle.read(root);
    Json.write(new WorkspaceStyleData(new FormatterSpec("none", List.of()), null), committed);
    final WorkspaceStyleData formatterOnly = WorkspaceStyle.read(root);

    assertThat(indentOnly.formatter().engine()).isEqualTo("google");
    assertThat(indentOnly.indent().block()).isEqualTo(4);
    assertThat(formatterOnly.formatter().engine()).isEqualTo("none");
    assertThat(formatterOnly.indent().block()).isEqualTo(2);
  }

  // A declaration without jars runs from the jars the sync resolved for it, as long as the sync's
  // spec is its completion: the same engine, version and options, the Eclipse profile made
  // absolute.
  @Test
  void read_declarationAndItsResolution_generatedSpecRuns() throws IOException {
    final Path profile = root.resolve("eclipse-formatter.xml");
    writeGenerated(
        new FormatterSpec(
            "eclipse",
            List.of(),
            "4.41",
            Map.of(LatheFlags.FORMAT_FILE, profile.toString(), "groupArtifact", "org.eclipse:jdt"),
            List.of("/repo/jdt.jar")),
        new IndentSpec("editorconfig", 0, 0));
    writeCommitted(
        new FormatterSpec(
            "eclipse",
            List.of(),
            "",
            Map.of(LatheFlags.FORMAT_FILE, "eclipse-formatter.xml"),
            null));

    final WorkspaceStyleData style = WorkspaceStyle.read(root);

    assertThat(style.formatter().classpath()).containsExactly("/repo/jdt.jar");
    assertThat(style.formatter().version()).isEqualTo("4.41");
    assertThat(style.indent().profile()).isEqualTo("editorconfig");
  }

  // A declaration the last sync did not resolve keeps running without jars, so the server reports
  // it unresolved rather than running another formatter.
  @Test
  void read_declarationDiffersFromResolution_committedSpecStays() throws IOException {
    writeGenerated(
        new FormatterSpec(
            "google",
            List.of(),
            "1.28.0",
            Map.of(LatheFlags.FORMAT_STYLE, "GOOGLE"),
            List.of("/repo/gjf.jar")),
        null);
    final List<FormatterSpec> declarations =
        List.of(
            new FormatterSpec("palantir", List.of()),
            new FormatterSpec("google", List.of(), "1.30.0", Map.of(), null),
            new FormatterSpec(
                "google", List.of(), "", Map.of(LatheFlags.FORMAT_STYLE, "AOSP"), null));

    for (final FormatterSpec declared : declarations) {
      writeCommitted(declared);

      assertThat(WorkspaceStyle.read(root).formatter()).isEqualTo(declared);
    }
  }

  @Test
  void read_malformedFile_throws() throws IOException {
    Files.writeString(root.resolve(LatheLayout.STYLE_SHARED_FILE), "{ not json");

    assertThatThrownBy(() -> WorkspaceStyle.read(root)).isInstanceOf(IOException.class);
  }

  private void writeCommitted(final FormatterSpec formatter) throws IOException {
    Json.write(
        new WorkspaceStyleData(formatter, null), root.resolve(LatheLayout.STYLE_SHARED_FILE));
  }

  private void writeGenerated(final FormatterSpec formatter, final IndentSpec indent)
      throws IOException {
    final Path latheDir = root.resolve(LatheLayout.LATHE_DIR);
    Files.createDirectories(latheDir);
    Json.write(new WorkspaceStyleData(formatter, indent), latheDir.resolve(LatheLayout.STYLE_FILE));
  }
}
