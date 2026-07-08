package io.github.aglibs.lathe.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.LaunchMode;
import io.github.aglibs.lathe.core.schema.TestLaunchData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LaunchTemplateReaderTest {

  @TempDir private Path workspaceRoot;

  @Test
  void read_missingLaunchFile_returnsEmpty() throws IOException {
    final var reader = new LaunchTemplateReader(workspaceRoot);

    assertThat(reader.read("app")).isEmpty();
  }

  @Test
  void read_presentLaunchFile_returnsData() throws IOException {
    final Path moduleDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve("app");
    Files.createDirectories(moduleDir);
    final var data =
        new TestLaunchData(
            "1",
            "surefire",
            LaunchMode.CLASSPATH,
            "/jdk",
            "",
            List.of(),
            List.of("/m2/junit.jar"),
            Map.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    Json.write(data, moduleDir.resolve(LatheLayout.TEST_LAUNCH_FILE));

    final var reader = new LaunchTemplateReader(workspaceRoot);

    assertThat(reader.read("app")).contains(data);
  }
}
