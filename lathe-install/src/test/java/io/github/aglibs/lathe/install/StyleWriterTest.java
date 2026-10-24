package io.github.aglibs.lathe.install;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.FormatterSpec;
import io.github.aglibs.lathe.core.schema.IndentSpec;
import io.github.aglibs.lathe.core.schema.WorkspaceStyleData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StyleWriterTest {

  @TempDir Path workspaceRoot;

  @Test
  void write_styleWrittenOnce_unchangedStyleLeavesTheFileAlone() throws Exception {
    final var style =
        new WorkspaceStyleData(
            new FormatterSpec("google", List.of(), "1.24.0", Map.of(), List.of("/repo/gjf.jar")),
            new IndentSpec("google", 2, 4));
    StyleWriter.write(workspaceRoot, style);
    final Path file = workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.STYLE_FILE);
    final var stamp = FileTime.fromMillis(1_000_000L);
    Files.setLastModifiedTime(file, stamp);

    StyleWriter.write(workspaceRoot, style);

    assertThat(Files.getLastModifiedTime(file)).isEqualTo(stamp);
    assertThat(Json.read(file, WorkspaceStyleData.class)).isEqualTo(style);
  }
}
