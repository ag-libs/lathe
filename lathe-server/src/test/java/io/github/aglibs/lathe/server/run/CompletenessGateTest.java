package io.github.aglibs.lathe.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.launch.ReactorRewrite;
import io.github.aglibs.lathe.core.schema.LaunchMode;
import io.github.aglibs.lathe.core.schema.MainLaunchData;
import io.github.aglibs.lathe.core.schema.TestLaunchData;
import io.github.aglibs.lathe.server.TestCompiler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CompletenessGateTest {

  @TempDir private Path workspaceRoot;

  private ReactorRewrite rewrite() {
    return TestCompiler.targetRewrite(workspaceRoot, "dep", "app", "jpms");
  }

  @Test
  void verify_builtByMavenButNotMirrored_returnsIncompleteWithReasons() throws IOException {
    final var data = testLaunch();
    mavenBuilt("dep/target/classes");
    mavenBuilt("app/target/test-classes");

    final CompletenessResult result = CompletenessGate.verify(data, workspaceRoot, rewrite());

    assertThat(result.complete()).isFalse();
    assertThat(result.reasons()).hasSize(2);
  }

  @Test
  void verify_paramsPresentOutputsPopulated_returnsOk() throws IOException {
    final var data = testLaunch();
    seedModule("dep", LatheLayout.CLASSES_DIR);
    seedModule("app", LatheLayout.TEST_CLASSES_DIR);

    final CompletenessResult result = CompletenessGate.verify(data, workspaceRoot, rewrite());

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

    final CompletenessResult result = CompletenessGate.verify(data, workspaceRoot, rewrite());

    assertThat(result.complete()).isFalse();
    assertThat(result.reasons()).hasSize(1);
    assertThat(result.reasons().get(0))
        .isEqualTo(
            "app test classes are not compiled into .lathe yet — run %s app"
                .formatted(LatheLayout.SYNC_SCRIPT_COMMAND));
  }

  @Test
  void verify_mainBuiltByMavenButNotMirrored_returnsIncompleteWithReason() throws IOException {
    final var data = mainLaunch();
    mavenBuilt("jpms/target/classes");

    final CompletenessResult result = CompletenessGate.verify(data, workspaceRoot, rewrite());

    assertThat(result.complete()).isFalse();
    assertThat(result.reasons()).hasSize(1);
    assertThat(result.reasons().get(0))
        .isEqualTo(
            "jpms classes are not compiled into .lathe yet — run %s jpms"
                .formatted(LatheLayout.SYNC_SCRIPT_COMMAND));
  }

  @Test
  void verify_mainOutputPopulated_returnsOk() throws IOException {
    final var data = mainLaunch();
    seedModule("jpms", LatheLayout.CLASSES_DIR);

    final CompletenessResult result = CompletenessGate.verify(data, workspaceRoot, rewrite());

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

    final CompletenessResult blocked = CompletenessGate.verify(data, workspaceRoot, rewrite());
    seedModule("dep", LatheLayout.CLASSES_DIR);
    final CompletenessResult open = CompletenessGate.verify(data, workspaceRoot, rewrite());

    assertThat(blocked.reasons())
        .containsExactly(
            "dep classes are not compiled into .lathe yet — run %s dep"
                .formatted(LatheLayout.SYNC_SCRIPT_COMMAND));
    assertThat(open.complete()).isTrue();
  }

  // A build that tests a copy of its classes (a multi-release overlay in classes-jdk21): no mirror
  // stands for the copy, so the run says it takes it from the last build instead of running it
  // silently; mirrored outputs and directories outside any build directory say nothing.
  @Test
  void verify_copyOfClassesInABuildDir_openWithNote_mirroredAndOutsideDirsNot() throws IOException {
    seedModule("dep", LatheLayout.CLASSES_DIR);
    seedModule("app", LatheLayout.TEST_CLASSES_DIR);
    mavenBuilt("dep/target/classes-jdk21");
    mavenBuilt("tools/lib");
    final var data =
        testLaunch(
            List.of(workspaceRoot.resolve("dep/target/classes-jdk21").toString()),
            List.of(
                workspaceRoot.resolve("app/target/test-classes").toString(),
                workspaceRoot.resolve("tools/lib").toString(),
                jar("m2/junit.jar")));

    final CompletenessResult result = CompletenessGate.verify(data, workspaceRoot, rewrite());

    assertThat(result.complete()).isTrue();
    assertThat(result.notes())
        .singleElement()
        .asString()
        .startsWith("dep/target/classes-jdk21 runs as the last build left it");
    assertThat(CompletenessGate.verify(testLaunch(), workspaceRoot, rewrite()).notes()).isEmpty();
  }

  // A jar the build deleted after the capture (Develocity's test launcher copies its jars to a
  // temporary directory) blocks the run, naming it, rather than failing midway on a missing class;
  // an output directory that was never created does not.
  @Test
  void verify_capturedJarDeleted_blockedNamingIt_missingDirectoryNot() throws IOException {
    seedModule("dep", LatheLayout.CLASSES_DIR);
    seedModule("app", LatheLayout.TEST_CLASSES_DIR);
    final Path deleted = workspaceRoot.resolve("tmp/test-launcher/opentest4j-1.3.0.jar");
    final var data =
        testLaunch(
            List.of(workspaceRoot.resolve("dep/target/classes").toString()),
            List.of(
                workspaceRoot.resolve("app/target/test-classes").toString(),
                workspaceRoot.resolve("app/build/resources/main").toString(),
                jar("m2/junit.jar"),
                deleted.toString()));

    final CompletenessResult result = CompletenessGate.verify(data, workspaceRoot, rewrite());

    assertThat(result.complete()).isFalse();
    assertThat(result.reasons())
        .singleElement()
        .asString()
        .startsWith("%s no longer exists".formatted(deleted));
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

  private MainLaunchData mainLaunch() throws IOException {
    return new MainLaunchData(
        LaunchMode.MODULE,
        "/jdk",
        "com.example.jpms",
        List.of(workspaceRoot.resolve("jpms/target/classes").toString(), jar("m2/validcheck.jar")),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        "");
  }

  private TestLaunchData testLaunch() throws IOException {
    return testLaunch(
        List.of(workspaceRoot.resolve("dep/target/classes").toString()),
        List.of(workspaceRoot.resolve("app/target/test-classes").toString(), jar("m2/junit.jar")));
  }

  private String jar(final String relative) throws IOException {
    final Path jar = workspaceRoot.resolve(relative);
    Files.createDirectories(jar.getParent());
    Files.writeString(jar, "jar");
    return jar.toString();
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
