package io.github.aglibs.lathe.server.run;

import static org.assertj.core.api.Assertions.assertThat;

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

final class CompletenessGateTest {

  @TempDir private Path workspaceRoot;

  @Test
  void verify_builtByMavenButNotMirrored_returnsIncompleteWithReasons() throws IOException {
    final var data = testLaunch();
    mavenBuilt("dep/target/classes");
    mavenBuilt("app/target/test-classes");

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
    assertThat(result.reasons().getFirst())
        .isEqualTo(
            "app test classes are not compiled into .lathe yet — run .lathe/lathe-sync.sh app");
  }

  @Test
  void verify_mainBuiltByMavenButNotMirrored_returnsIncompleteWithReason() throws IOException {
    final var data = mainLaunch();
    mavenBuilt("jpms/target/classes");

    final CompletenessResult result = CompletenessGate.verify(data, workspaceRoot);

    assertThat(result.complete()).isFalse();
    assertThat(result.reasons()).hasSize(1);
    assertThat(result.reasons().getFirst())
        .isEqualTo("jpms classes are not compiled into .lathe yet — run .lathe/lathe-sync.sh jpms");
  }

  @Test
  void verify_mainOutputPopulated_returnsOk() throws IOException {
    final var data = mainLaunch();
    seedModule("jpms", LatheLayout.CLASSES_DIR);

    final CompletenessResult result = CompletenessGate.verify(data, workspaceRoot);

    assertThat(result.complete()).isTrue();
    assertThat(result.reasons()).isEmpty();
  }

  @Test
  void verify_testOnlyModuleOwnMainTreeNeverBuilt_skipped_otherTreesStillChecked()
      throws IOException {
    // Maven lists app's own target/classes although app has only tests: nothing ever built it.
    final var data =
        testLaunch(
            List.of(workspaceRoot.resolve("dep/target/classes").toString()),
            List.of(
                workspaceRoot.resolve("app/target/test-classes").toString(),
                workspaceRoot.resolve("app/target/classes").toString()));
    seedModule("app", LatheLayout.TEST_CLASSES_DIR);
    mavenBuilt("dep/target/classes");

    final CompletenessResult blocked = CompletenessGate.verify(data, workspaceRoot);
    seedModule("dep", LatheLayout.CLASSES_DIR);
    final CompletenessResult open = CompletenessGate.verify(data, workspaceRoot);

    assertThat(blocked.reasons())
        .containsExactly(
            "dep classes are not compiled into .lathe yet — run .lathe/lathe-sync.sh dep");
    assertThat(open.complete()).isTrue();
  }

  private void mavenBuilt(final String outputDir) throws IOException {
    Files.createDirectories(workspaceRoot.resolve(outputDir));
  }

  private void seedModule(final String moduleRel, final String sourceTree) throws IOException {
    final Path moduleDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(moduleRel);
    Files.createDirectories(moduleDir);
    Files.writeString(moduleDir.resolve(LatheLayout.paramsFileName(sourceTree)), "{}");
    final Path outputDir = moduleDir.resolve(sourceTree);
    Files.createDirectories(outputDir);
    Files.writeString(outputDir.resolve("Placeholder.class"), "bytes");
  }

  private MainLaunchData mainLaunch() {
    return new MainLaunchData(
        LaunchMode.MODULE,
        "/jdk",
        "com.example.jpms",
        List.of(workspaceRoot.resolve("jpms/target/classes").toString(), "/m2/validcheck.jar"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        "");
  }

  private TestLaunchData testLaunch() {
    return testLaunch(
        List.of(workspaceRoot.resolve("dep/target/classes").toString()),
        List.of(workspaceRoot.resolve("app/target/test-classes").toString(), "/m2/junit.jar"));
  }

  private TestLaunchData testLaunch(final List<String> modulePath, final List<String> classPath) {
    return new TestLaunchData(
        "surefire",
        LaunchMode.CLASSPATH,
        "/jdk",
        "",
        modulePath,
        classPath,
        Map.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        "");
  }
}
