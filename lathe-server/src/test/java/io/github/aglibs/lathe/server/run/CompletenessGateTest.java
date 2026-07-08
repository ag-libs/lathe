package io.github.aglibs.lathe.server.run;

import static org.assertj.core.api.Assertions.assertThat;

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

final class CompletenessGateTest {

  @TempDir private Path workspaceRoot;

  @Test
  void verify_missingOutputs_returnsIncompleteWithReasons() throws IOException {
    final var data = testLaunch();

    final CompletenessResult result = CompletenessGate.verify(data, workspaceRoot);

    assertThat(result.complete()).isFalse();
    assertThat(result.reasons()).hasSize(2);
  }

  @Test
  void verify_paramsPresentOutputsPopulated_returnsOk() throws IOException {
    final var data = testLaunch();
    seedModule("dep", LatheLayout.CLASSES_DIR);
    seedModule("app", LatheLayout.TEST_CLASSES_DIR);

    final CompletenessResult result = CompletenessGate.verify(data, workspaceRoot);

    assertThat(result.complete()).isTrue();
    assertThat(result.reasons()).isEmpty();
  }

  @Test
  void verify_paramsPresentOutputEmpty_returnsIncomplete() throws IOException {
    final var data = testLaunch();
    seedModule("dep", LatheLayout.CLASSES_DIR);

    final Path appDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve("app");
    Files.createDirectories(appDir);
    Files.writeString(
        appDir.resolve(LatheLayout.paramsFileName(LatheLayout.TEST_CLASSES_DIR)), "{}");
    Files.createDirectories(appDir.resolve(LatheLayout.TEST_CLASSES_DIR));

    final CompletenessResult result = CompletenessGate.verify(data, workspaceRoot);

    assertThat(result.complete()).isFalse();
    assertThat(result.reasons()).hasSize(1);
    assertThat(result.reasons().get(0)).contains("test-classes");
  }

  private void seedModule(final String moduleRel, final String sourceTree) throws IOException {
    final Path moduleDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(moduleRel);
    Files.createDirectories(moduleDir);
    Files.writeString(moduleDir.resolve(LatheLayout.paramsFileName(sourceTree)), "{}");
    final Path outputDir = moduleDir.resolve(sourceTree);
    Files.createDirectories(outputDir);
    Files.writeString(outputDir.resolve("Placeholder.class"), "bytes");
  }

  private TestLaunchData testLaunch() {
    return new TestLaunchData(
        "1",
        "surefire",
        LaunchMode.CLASSPATH,
        "/jdk",
        "",
        List.of(workspaceRoot.resolve("dep/target/classes").toString()),
        List.of(workspaceRoot.resolve("app/target/test-classes").toString(), "/m2/junit.jar"),
        Map.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }
}
