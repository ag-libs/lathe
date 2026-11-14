package io.github.aglibs.lathe.openjdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.lathe.core.CompiledStamps;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheLock;
import io.github.aglibs.lathe.core.schema.BuildFileData;
import io.github.aglibs.lathe.core.schema.SourceStamp;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(value = OS.WINDOWS, disabledReason = "OpenJDK builds on Windows are not supported")
class OpenJdkSyncTest {

  @TempDir private Path root;

  private Path build;
  private Path latheDir;

  @BeforeEach
  void builtJdk() throws IOException {
    build = root.resolve("build/linux-x86_64-server-release");
    latheDir = root.resolve(LatheLayout.LATHE_DIR);
    // One built module: its cmdline (no module-source-path, so no source roots) and make's marker.
    final Path module = build.resolve("jdk/modules/java.base");
    write(
        module.resolve("_the.java.base_batch.cmdline"),
        "/boot/bin/java -cp x com.sun.tools.javac.Main -source 21 -target 21");
    write(module.resolve("_the.java.base_batch"), "");
    // One build tool: a plain classpath compile, its source, compiled class, filelist and marker.
    final Path tool = build.resolve("buildtools/tools_classes");
    final Path source = root.resolve("make/src/pkg/Tool.java");
    write(source, "package pkg; class Tool {}");
    write(tool.resolve("pkg/Tool.class"), "");
    write(
        tool.resolve("_the.BUILD_TOOLS_batch.cmdline"),
        "/boot/bin/javac -source 27 -target 27 -cp /x -d %s".formatted(tool));
    write(tool.resolve("_the.BUILD_TOOLS_batch.filelist"), source.toString());
    write(tool.resolve("_the.BUILD_TOOLS_batch"), "");
  }

  @Test
  void sync_builtModuleAndTool_writesPinnedScriptAndBatchMarkersAndReleasesLock()
      throws IOException {
    OpenJdkSync.sync(build, root, "1.2.3", bootMaven(), message -> {});

    final Path script = latheDir.resolve(LatheLayout.SYNC_SCRIPT);
    assertThat(script).isExecutable();
    assertThat(Files.readString(script))
        .contains("lathe-openjdk-maven-plugin:1.2.3:sync")
        .contains("-Dlathe.buildDir=%s".formatted(build.toAbsolutePath()))
        .contains("tool='/opt/maven/bin/mvn'")
        .contains("export JAVA_HOME='/boot'")
        .doesNotContain("java-home")
        .doesNotContain("make ");
    final var manifest =
        Json.read(latheDir.resolve(LatheLayout.WORKSPACE_JSON), WorkspaceManifestData.class);
    assertThat(manifest.buildTool()).isEqualTo("/opt/maven/bin/mvn");
    assertThat(manifest.buildFiles())
        .as("markers make only touches, so they are watched by touch, not content")
        .containsExactly(
            new BuildFileData(
                "build/linux-x86_64-server-release/jdk/modules/java.base/_the.java.base_batch",
                null),
            new BuildFileData(
                "build/linux-x86_64-server-release/buildtools/tools_classes/_the.BUILD_TOOLS_batch",
                null));
    final Path stamps =
        FileUtil.walkFiles(
                latheDir,
                file ->
                    file.getFileName()
                        .toString()
                        .equals(LatheLayout.compiledStampsFileName("classes")))
            .get(0);
    assertThat(CompiledStamps.load(stamps.getParent(), "classes").values())
        .as("the JDK tree is not fingerprinted, so the mtime alone decides")
        .singleElement()
        .extracting(SourceStamp::content)
        .isNull();
    assertThat(LatheLayout.reactorLockFile(latheDir)).doesNotExist();
  }

  // A module mid-rebuild has its output but no .cmdline yet, so it keeps its capture; a module or
  // tool whose output is gone loses it.
  @Test
  void sync_outputGone_dropsItsCapture_moduleMidRebuildKeepsIt() throws IOException {
    OpenJdkSync.sync(build, root, "1.2.3", bootMaven(), message -> {});
    assertThat(capturedDirs()).containsExactlyInAnyOrder("java.base", "BUILD_TOOLS");

    Files.delete(build.resolve("jdk/modules/java.base/_the.java.base_batch.cmdline"));
    OpenJdkSync.sync(build, root, "1.2.3", bootMaven(), message -> {});
    assertThat(capturedDirs()).containsExactlyInAnyOrder("java.base", "BUILD_TOOLS");

    FileUtil.deleteDir(build.resolve("jdk/modules/java.base"));
    FileUtil.deleteDir(build.resolve("buildtools/tools_classes"));
    OpenJdkSync.sync(build, root, "1.2.3", bootMaven(), message -> {});
    assertThat(capturedDirs()).isEmpty();
  }

  // A source the last make listed but deleted since has no stamp; the sync still completes.
  @Test
  void sync_listedSourceDeletedSinceTheMake_syncsWithoutIt() throws IOException {
    Files.delete(root.resolve("make/src/pkg/Tool.java"));

    OpenJdkSync.sync(build, root, "1.2.3", bootMaven(), message -> {});

    final Path stamps =
        FileUtil.walkFiles(
                latheDir,
                file ->
                    file.getFileName()
                        .toString()
                        .equals(LatheLayout.compiledStampsFileName("classes")))
            .get(0);
    assertThat(CompiledStamps.load(stamps.getParent(), "classes")).isEmpty();
  }

  private List<String> capturedDirs() throws IOException {
    return FileUtil.walkFiles(latheDir, LatheLayout::isParamsFile).stream()
        .map(params -> params.getParent().getFileName().toString())
        .toList();
  }

  @Test
  void sync_writeFailsMidway_throwsAndReleasesLock() throws IOException {
    // A directory where workspace.json goes makes the write fail after the lock is taken.
    Files.createDirectories(latheDir.resolve(LatheLayout.WORKSPACE_JSON).resolve("blocker"));

    assertThatThrownBy(() -> OpenJdkSync.sync(build, root, "1.2.3", bootMaven(), message -> {}))
        .isInstanceOf(IOException.class);
    assertThat(LatheLayout.reactorLockFile(latheDir)).doesNotExist();
  }

  // The Maven that runs the goal, on the boot JDK; never the JDK being built.
  private static Properties bootMaven() {
    final var properties = new Properties();
    properties.setProperty("maven.home", "/opt/maven");
    properties.setProperty("java.home", "/boot");
    return properties;
  }

  private static void write(final Path file, final String content) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  // The whole sync runs under .lathe/'s build lock, which a first sync creates .lathe/ to hold,
  // and which is released after, the sync failing or not.
  // .lathe/ is ignored from when it is first created, so a first sync that fails leaves no
  // untracked files in the JDK checkout.
  @Test
  void underBuildLock_firstSyncFails_latheDirIsIgnored() {
    assertThatThrownBy(
            () ->
                OpenJdkSync.underBuildLock(
                    root,
                    () -> {
                      throw new IOException("bad cmdline");
                    }))
        .hasMessage("bad cmdline");
    assertThat(latheDir.resolve(".gitignore")).hasContent("*");
  }

  @Test
  void underBuildLock_firstSync_holdsTheLockThroughTheWorkAndReleasesIt() throws Exception {
    final Path lock = LatheLayout.reactorLockFile(root.resolve(LatheLayout.LATHE_DIR));
    final var heldDuringWork = new boolean[1];

    OpenJdkSync.underBuildLock(root, () -> heldDuringWork[0] = LatheLock.isBuilding(lock));
    assertThat(heldDuringWork[0]).isTrue();
    assertThat(LatheLock.isBuilding(lock)).isFalse();

    assertThatThrownBy(
            () ->
                OpenJdkSync.underBuildLock(
                    root,
                    () -> {
                      throw new IOException("make failed");
                    }))
        .hasMessage("make failed");
    assertThat(LatheLock.isBuilding(lock)).isFalse();
  }
}
