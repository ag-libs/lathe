package io.github.aglibs.lathe.server.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import io.github.aglibs.lathe.analysis.LibrarySources;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.DependencyData;
import io.github.aglibs.lathe.core.schema.JdkSourceData;
import io.github.aglibs.lathe.core.schema.OutputData;
import io.github.aglibs.lathe.core.schema.SourceStatus;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceManifestTest {

  @TempDir private Path tmp;

  @Test
  void load_missingFile_returnsEmpty(@TempDir final Path dir) {
    assertThat(WorkspaceManifest.load(dir).libraries()).isEqualTo(LibrarySources.none());
  }

  @Test
  void load_schemaVersion_matchLoadsDataAndOutputsMismatchLoadsEmptyWithRemediation()
      throws Exception {
    final List<String> runnerClasspath = List.of(tmp.resolve("cache/runner.jar").toString());
    final List<OutputData> outputs =
        List.of(new OutputData("app/target/classes", "app", LatheLayout.CLASSES_DIR));
    final Path appClasses = tmp.resolve("app/target/classes");
    writeWorkspaceManifest(LatheLayout.SCHEMA_VERSION, List.of(), null, runnerClasspath, outputs);
    final var matching = WorkspaceManifest.load(tmp);
    writeWorkspaceManifest("0", List.of(), null, runnerClasspath, outputs);

    final var mismatched = WorkspaceManifest.load(tmp);

    assertThat(matching.loadProblem()).isEmpty();
    assertThat(matching.runnerClasspath()).isNotEmpty();
    assertThat(matching.reactorRewrite().toLathe(appClasses))
        .isEqualTo(tmp.resolve(".lathe/app/classes"));
    assertThat(mismatched.runnerClasspath()).isEmpty();
    assertThat(mismatched.reactorRewrite().toLathe(appClasses)).isEqualTo(appClasses);
    assertThat(mismatched.loadProblem())
        .hasValueSatisfying(
            text ->
                assertThat(text)
                    .contains("schema 0, expected %s".formatted(LatheLayout.SCHEMA_VERSION))
                    .contains(LatheLayout.SYNC_SCRIPT_COMMAND));
  }

  @Test
  void load_unreadableFile_loadsEmptyWithTheCauseAndRemediation() throws Exception {
    final Path latheDir = tmp.resolve(LatheLayout.LATHE_DIR);
    Files.createDirectories(latheDir);
    Files.writeString(latheDir.resolve(LatheLayout.WORKSPACE_JSON), "{\"schemaVersion\": [");

    final var manifest = WorkspaceManifest.load(tmp);

    assertThat(manifest.typeIndexShardPaths()).isEmpty();
    assertThat(manifest.loadProblem())
        .hasValueSatisfying(
            text ->
                assertThat(text)
                    .contains("could not be read (")
                    .contains("`%s`".formatted(LatheLayout.SYNC_SCRIPT_COMMAND)));
  }

  @Test
  void runnerClasspath_empty_returnsEmpty() {
    assertThat(WorkspaceManifest.empty().runnerClasspath()).isEmpty();
  }

  @Test
  void runnerClasspath_presentInManifest_returnsPaths() throws Exception {
    final Path runnerJar = tmp.resolve("cache/lathe-test-runner.jar");
    final Path launcherJar = tmp.resolve("cache/junit-platform-launcher.jar");
    writeWorkspaceManifest(List.of(), null, List.of(runnerJar.toString(), launcherJar.toString()));

    assertThat(WorkspaceManifest.load(tmp).runnerClasspath())
        .containsExactly(runnerJar, launcherJar);
  }

  // A test-jar dependency and a tests-classifier one name the same jar: the manifest loads, with
  // its libraries and type-index shards from the first entry.
  @Test
  void load_jarListedTwice_loadsWithTheFirstEntry() throws Exception {
    final Path jar = tmp.resolve("repo/lib-1-tests.jar");
    final Path sourceDir = tmp.resolve("sources/lib");
    final Path typeIndex = tmp.resolve("cache/type-index/lib.json");
    final var first =
        new DependencyData(
            "com.example:lib:test-jar:tests:1",
            jar.toString(),
            SourceStatus.PRESENT,
            sourceDir.toString(),
            List.of(),
            typeIndex.toString());
    final var second =
        new DependencyData(
            "com.example:lib:jar:tests:1",
            jar.toString(),
            SourceStatus.PRESENT,
            sourceDir.toString(),
            List.of(),
            typeIndex.toString());
    writeWorkspaceManifest(List.of(first, second));

    final WorkspaceManifest manifest = WorkspaceManifest.load(tmp);

    assertThat(manifest.loadProblem()).isEmpty();
    assertThat(manifest.libraries().jarToGav()).containsExactly(entry(jar, first.gav()));
    assertThat(manifest.libraries().dependencySourceDir(jar)).hasValue(sourceDir);
    assertThat(manifest.typeIndexShardPaths()).containsExactly(typeIndex);
  }

  @Test
  void load_jdkTypeIndex_includesShardPath() throws Exception {
    final Path typeIndex = tmp.resolve("cache/type-index/jdks/vendor/21/index.json");
    writeWorkspaceManifest(
        List.of(),
        new JdkSourceData(
            "OpenJDK", "21.0.1", SourceStatus.MISSING, Path.of("/jdk"), null, null, typeIndex),
        List.of());

    assertThat(WorkspaceManifest.load(tmp).typeIndexShardPaths()).containsExactly(typeIndex);
  }

  private void writeWorkspaceManifest(final List<DependencyData> deps) throws Exception {
    writeWorkspaceManifest(deps, null, List.of());
  }

  private void writeWorkspaceManifest(
      final List<DependencyData> deps, final JdkSourceData jdk, final List<String> runnerClasspath)
      throws Exception {
    writeWorkspaceManifest(LatheLayout.SCHEMA_VERSION, deps, jdk, runnerClasspath, List.of());
  }

  private void writeWorkspaceManifest(
      final String schemaVersion,
      final List<DependencyData> deps,
      final JdkSourceData jdk,
      final List<String> runnerClasspath,
      final List<OutputData> outputs)
      throws Exception {
    final Path latheDir = tmp.resolve(LatheLayout.LATHE_DIR);
    Files.createDirectories(latheDir);
    Json.write(
        new WorkspaceManifestData(
            schemaVersion,
            tmp.toString(),
            null,
            runnerClasspath,
            jdk,
            deps,
            List.of(),
            List.of(),
            outputs,
            null),
        latheDir.resolve(LatheLayout.WORKSPACE_JSON));
  }
}
