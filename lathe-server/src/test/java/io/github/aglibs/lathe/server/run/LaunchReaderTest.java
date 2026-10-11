package io.github.aglibs.lathe.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.LaunchMode;
import io.github.aglibs.lathe.core.schema.MainLaunchData;
import io.github.aglibs.lathe.core.schema.TestLaunchData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LaunchReaderTest {

  @TempDir private Path workspaceRoot;

  @Test
  void read_missingLaunchFiles_returnsEmpty() throws IOException {
    final var reader = new LaunchReader(workspaceRoot);

    assertThat(reader.test("app")).isEmpty();
    assertThat(reader.main("app")).isEmpty();
  }

  @Test
  void read_presentLaunchFiles_returnsData() throws IOException {
    final var testLaunch =
        new TestLaunchData(
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
            List.of(),
            "");
    final var mainLaunch =
        new MainLaunchData(
            LaunchMode.MODULE,
            "/jdk",
            "com.example.jpms",
            List.of("/ws/jpms/target/classes", "/m2/validcheck.jar"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "");
    writeLaunch("app", LatheLayout.TEST_LAUNCH_FILE, testLaunch);
    writeLaunch("jpms", LatheLayout.MAIN_LAUNCH_FILE, mainLaunch);

    final var reader = new LaunchReader(workspaceRoot);

    assertThat(reader.test("app")).contains(testLaunch);
    assertThat(reader.main("jpms")).contains(mainLaunch);
  }

  private void writeLaunch(final String moduleRel, final String fileName, final Object data)
      throws IOException {
    final Path moduleDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(moduleRel);
    Files.createDirectories(moduleDir);
    Json.write(data, moduleDir.resolve(fileName));
  }
}
