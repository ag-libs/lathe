package io.github.aglibs.lathe.server.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.DependencyData;
import io.github.aglibs.lathe.core.schema.JdkSourceData;
import io.github.aglibs.lathe.core.schema.OutputData;
import io.github.aglibs.lathe.core.schema.SourceStatus;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import io.github.aglibs.lathe.server.TestCompiler;
import io.github.aglibs.lathe.server.analysis.SampleFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceManifestTest extends SampleFixture {

  @Test
  void load_missingFile_returnsEmpty(@TempDir final Path dir) {
    assertThat(WorkspaceManifest.load(dir).originLabel(null, null)).isEmpty();
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

  @Test
  void originLabel_jdkType_returnsModuleName() {
    final var listType = compiled.task().getElements().getTypeElement("java.util.List");
    assertThat(WorkspaceManifest.empty().originLabel(listType, compiled.fm()))
        .hasValue("java.base");
  }

  @Test
  void originLabel_jdkType_withVersionInManifest_includesVersion() throws Exception {
    writeWorkspaceManifest(
        List.of(),
        new JdkSourceData("OpenJDK", "21.0.1", SourceStatus.MISSING, null, null, null, null),
        List.of());

    final var listType = compiled.task().getElements().getTypeElement("java.util.List");
    assertThat(WorkspaceManifest.load(tmp).originLabel(listType, compiled.fm()))
        .hasValue("java.base (JDK 21.0.1)");
  }

  @Test
  void originLabel_reactorClass_returnsModuleName() throws Exception {
    final Path classDir = tmp.resolve(LatheLayout.LATHE_DIR).resolve("mymodule");
    Files.createDirectories(classDir);
    final Path sampleSrc = Path.of(compiled.cu().getSourceFile().toUri());
    TestCompiler.compileToDir(classDir, sampleSrc);

    try (final var parsed = TestCompiler.parseWithClasspath(sampleSrc, classDir)) {
      final var sampleType = parsed.task().getElements().getTypeElement("Sample");

      assertThat(WorkspaceManifest.empty().originLabel(sampleType, parsed.fm()))
          .hasValue("mymodule");
    }
  }

  @Test
  void load_dependencySourceClasspath_returnsSelfJarAndTransitiveJars() throws Exception {
    final Path selfJar = tmp.resolve("repo/self.jar");
    final Path transitiveJar = tmp.resolve("repo/transitive.jar");
    final Path sourceDir = tmp.resolve("sources/self");
    final Path sourceFile = sourceDir.resolve("com/example/Self.java");
    writeWorkspaceManifest(
        List.of(
            new DependencyData(
                "com.example:self:1",
                selfJar.toString(),
                SourceStatus.PRESENT,
                sourceDir.toString(),
                List.of(transitiveJar.toString()),
                null)));
    Files.createDirectories(sourceFile.getParent());
    Files.writeString(sourceFile, "package com.example; public class Self {}");

    final WorkspaceManifest manifest = WorkspaceManifest.load(tmp);

    assertThat(manifest.externalSourceRootForFile(sourceFile)).hasValue(sourceDir);
    assertThat(manifest.containsFile(sourceFile)).isTrue();
    assertThat(manifest.externalSourceDirs()).containsExactly(sourceDir);
    assertThat(manifest.depClasspathForFile(sourceFile)).containsExactly(selfJar, transitiveJar);
  }

  // Per-platform classifier jars of one artifact share its sources dir.
  @Test
  void load_classifierJarsSharingASourceDir_loadsWithTheFirstJar() throws Exception {
    final Path sourceDir = tmp.resolve("sources/natives");
    final Path sourceFile = sourceDir.resolve("com/example/Natives.java");
    final Path linuxJar = tmp.resolve("repo/natives-linux-x86_64.jar");
    final Path osxJar = tmp.resolve("repo/natives-osx-aarch_64.jar");
    final Path otherJar = tmp.resolve("repo/other.jar");
    writeWorkspaceManifest(
        List.of(
            new DependencyData(
                "com.example:natives:1",
                linuxJar.toString(),
                SourceStatus.PRESENT,
                sourceDir.toString(),
                List.of(otherJar.toString()),
                null),
            new DependencyData(
                "com.example:natives:1",
                osxJar.toString(),
                SourceStatus.PRESENT,
                sourceDir.toString(),
                List.of(),
                null)));

    final WorkspaceManifest manifest = WorkspaceManifest.load(tmp);

    assertThat(manifest.externalSourceDirs()).containsExactly(sourceDir);
    assertThat(manifest.depClasspathForFile(sourceFile))
        .startsWith(linuxJar, otherJar)
        .contains(osxJar);
  }

  // A test-jar dependency and a tests-classifier one name the same jar.
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
    assertThat(manifest.dependencyJarsToGav()).containsExactly(entry(jar, first.gav()));
    assertThat(manifest.dependencySourceDir(jar)).hasValue(sourceDir);
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

  // The jar URI is percent-encoded; a space in the path (C:\Users\First Last\.m2) must survive.
  @Test
  void externalSourceRoot_classpathJarUnderAPathWithASpace_returnsMappedSourceDir()
      throws Exception {
    final Path sourceDir = tmp.resolve("sources/greeter");
    final Path greeterSrc = sourceDir.resolve("com/example/Greeter.java");
    Files.createDirectories(greeterSrc.getParent());
    Files.writeString(greeterSrc, "package com.example; public class Greeter {}");

    final Path jar = tmp.resolve("First Last/repo/greeter.jar");
    Files.createDirectories(jar.getParent());
    TestCompiler.compileToJar(jar, tmp.resolve("classes/greeter"), List.of(), greeterSrc);
    writeWorkspaceManifest(
        List.of(
            new DependencyData(
                "com.example:greeter:1",
                jar.toString(),
                SourceStatus.PRESENT,
                sourceDir.toString(),
                List.of(),
                null)));

    final Path userSrc = tmp.resolve("User.java");
    Files.writeString(userSrc, "import com.example.Greeter; public class User { Greeter g; }");
    try (final var parsed = TestCompiler.parseWithClasspath(userSrc, jar)) {
      final var greeterElement = parsed.task().getElements().getTypeElement("com.example.Greeter");

      assertThat(WorkspaceManifest.load(tmp).externalSourceRoot(greeterElement, parsed.fm()))
          .hasValue(sourceDir);
    }
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
