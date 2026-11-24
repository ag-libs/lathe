package io.github.aglibs.lathe.server.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.LaunchMode;
import io.github.aglibs.lathe.core.schema.MainLaunchData;
import io.github.aglibs.lathe.core.schema.TestLaunchData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    assertThat(reader.test("app", LatheLayout.TEST_CLASSES_DIR)).isEmpty();
    assertThat(reader.main("app")).isEmpty();
    assertThat(reader.testCaptured("app")).isFalse();
  }

  // An older Lathe's capture (a field this one requires is missing) and a damaged one are no
  // capture: nothing to run, and the module does not count as captured.
  @Test
  void test_captureThisLatheCannotRead_readsAsNotCaptured() throws IOException {
    final Path moduleDir =
        Files.createDirectories(workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve("app"));
    final var reader = new LaunchReader(workspaceRoot);

    for (final String content : List.of("{\"framework\": \"surefire\"}", "{ not json")) {
      Files.writeString(
          moduleDir.resolve(LatheLayout.TEST_LAUNCH_FILE), content, StandardCharsets.UTF_8);

      assertThat(reader.test("app", LatheLayout.TEST_CLASSES_DIR)).as(content).isEmpty();
      assertThat(reader.testCaptured("app")).as(content).isFalse();
    }
  }

  // A capture that cannot be read at all (here a directory in its place, as a permission or I/O
  // error would) is not mended by a new capture: the run fails on it, and the summary counts it.
  @Test
  void test_captureThatCannotBeReadAtAll_failsTheRunAndStillCounts() throws IOException {
    Files.createDirectories(
        workspaceRoot
            .resolve(LatheLayout.LATHE_DIR)
            .resolve("app")
            .resolve(LatheLayout.TEST_LAUNCH_FILE));
    final var reader = new LaunchReader(workspaceRoot);

    assertThatThrownBy(() -> reader.test("app", LatheLayout.TEST_CLASSES_DIR))
        .isInstanceOf(IOException.class);
    assertThat(reader.testCaptured("app")).isTrue();
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
    final String integrationTree = LatheLayout.sourceSetTree("integrationTest");
    writeLaunch("gradle", LatheLayout.testLaunchFile(integrationTree), testLaunch);

    final var reader = new LaunchReader(workspaceRoot);

    assertThat(reader.test("app", LatheLayout.TEST_CLASSES_DIR)).contains(testLaunch);
    assertThat(reader.testCaptured("app")).isTrue();
    assertThat(reader.main("jpms")).contains(mainLaunch);
    // A Gradle source set's tests replay from its tree's own launch, not the module's.
    assertThat(reader.test("gradle", integrationTree)).contains(testLaunch);
    assertThat(reader.test("gradle", LatheLayout.TEST_CLASSES_DIR)).isEmpty();
  }

  private void writeLaunch(final String moduleRel, final String fileName, final Object data)
      throws IOException {
    final Path moduleDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(moduleRel);
    Files.createDirectories(moduleDir);
    Json.write(data, moduleDir.resolve(fileName));
  }
}
