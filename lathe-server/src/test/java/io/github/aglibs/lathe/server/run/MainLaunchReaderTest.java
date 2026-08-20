package io.github.aglibs.lathe.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.LaunchMode;
import io.github.aglibs.lathe.core.schema.MainLaunchData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MainLaunchReaderTest {

  @TempDir private Path workspaceRoot;

  @Test
  void read_missingLaunchFile_returnsEmpty() throws IOException {
    final var reader = new MainLaunchReader(workspaceRoot);

    assertThat(reader.read("app")).isEmpty();
  }

  @Test
  void read_presentLaunchFile_returnsData() throws IOException {
    final Path moduleDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve("jpms");
    Files.createDirectories(moduleDir);
    final var data =
        new MainLaunchData(
            "1",
            LaunchMode.MODULE,
            "/jdk",
            "com.example.jpms",
            List.of("/ws/jpms/target/classes", "/m2/validcheck.jar"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    Json.write(data, moduleDir.resolve(LatheLayout.MAIN_LAUNCH_FILE));

    final var reader = new MainLaunchReader(workspaceRoot);

    assertThat(reader.read("jpms")).contains(data);
  }
}
