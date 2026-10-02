package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.lathe.core.schema.FormatterSpec;
import io.github.aglibs.lathe.core.schema.IndentSpec;
import io.github.aglibs.lathe.core.schema.WorkspaceStyleData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

  @Test
  void read_malformedFile_throws() throws IOException {
    Files.writeString(root.resolve(LatheLayout.STYLE_SHARED_FILE), "{ not json");

    assertThatThrownBy(() -> WorkspaceStyle.read(root)).isInstanceOf(IOException.class);
  }

  private void writeGenerated(final FormatterSpec formatter, final IndentSpec indent)
      throws IOException {
    final Path latheDir = root.resolve(LatheLayout.LATHE_DIR);
    Files.createDirectories(latheDir);
    Json.write(new WorkspaceStyleData(formatter, indent), latheDir.resolve(LatheLayout.STYLE_FILE));
  }
}
