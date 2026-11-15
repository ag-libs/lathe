package io.github.aglibs.lathe.server.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import io.github.aglibs.lathe.analysis.LibrarySources;
import io.github.aglibs.lathe.analysis.testing.TestJavac;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.launch.ReactorRewrite;
import io.github.aglibs.lathe.core.schema.DependencyData;
import io.github.aglibs.lathe.core.schema.JdkSourceData;
import io.github.aglibs.lathe.core.schema.OutputData;
import io.github.aglibs.lathe.core.schema.SourceStatus;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import io.github.aglibs.lathe.server.TestCompiler;
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

  // Lathe reads no Kotlin: a built module with Kotlin sources where the Kotlin plugins look by
  // default, in any source set, is named, so an answer about a symbol's uses can say it did not
  // search there; the root module too.
  @Test
  void kotlinModuleDirs_moduleWithKotlinSources_listed_javaModuleNot() throws Exception {
    Files.createDirectories(tmp.resolve("app-kotlin/src/integrationTest/kotlin"));
    Files.createDirectories(tmp.resolve("app/src/main/java"));
    Files.createDirectories(tmp.resolve("src/main/kotlin"));
    writeWorkspaceManifest(
        LatheLayout.SCHEMA_VERSION,
        List.of(),
        null,
        List.of(),
        List.of(
            new OutputData("app/target/classes", "app", LatheLayout.CLASSES_DIR),
            new OutputData(
                "app-kotlin/target/test-classes", "app-kotlin", LatheLayout.TEST_CLASSES_DIR),
            new OutputData("target/classes", "", LatheLayout.CLASSES_DIR)));

    assertThat(WorkspaceManifest.load(tmp).kotlinModuleDirs())
        .containsExactlyInAnyOrder(tmp.resolve("app-kotlin"), tmp);
  }

  // A Gradle jar that packs a second source set's classes (generated code) is listed under its own
  // tree only: the jar's entries tell which other mirrors a class path holding it needs. A jar
  // built
  // before its classes moved to the test tree or a test source set still stands for its own tree
  // alone, and a path the build itself lists twice for its first tree.
  @Test
  void load_jarPackingAnotherTreesClasses_standsForBothMirrors_plainJarItsOwn() throws Exception {
    final Path lib = tmp.resolve(".lathe/lib");
    final Path main = source("lib", "Main");
    final Path gen = source("lib", "Gen");
    TestJavac.compileToDir(lib.resolve(LatheLayout.CLASSES_DIR), main);
    TestJavac.compileToDir(lib.resolve("classes-gen"), gen);
    TestJavac.compileToDir(lib.resolve("classes-jmh"), source("lib", "Bench"));
    TestCompiler.writeSourceSetParams(
        tmp, "lib", "gen", tmp.resolve("lib/src/gen/java"), List.of(), false);
    TestCompiler.writeSourceSetParams(
        tmp, "lib", "jmh", tmp.resolve("lib/src/jmh/java"), List.of(), false);
    final Path libJar = jar("lib", main, gen);
    final Path app = tmp.resolve(".lathe/app");
    final Path appMain = source("app", "App");
    final Path movedToTest = source("app", "MovedToTest");
    final Path movedToIt = source("app", "MovedToIt");
    TestJavac.compileToDir(app.resolve(LatheLayout.CLASSES_DIR), appMain);
    TestJavac.compileToDir(app.resolve(LatheLayout.TEST_CLASSES_DIR), movedToTest);
    TestJavac.compileToDir(app.resolve("classes-it"), movedToIt);
    TestCompiler.writeSourceSetParams(
        tmp, "app", "it", tmp.resolve("app/src/it/java"), List.of(), true);
    final Path appJar = jar("app", appMain, movedToTest, movedToIt);
    writeWorkspaceManifest(
        LatheLayout.SCHEMA_VERSION,
        List.of(),
        null,
        List.of(),
        List.of(
            new OutputData("lib/build/libs/lib.jar", "lib", LatheLayout.CLASSES_DIR),
            new OutputData("lib/build/classes/java/gen", "lib", "classes-gen"),
            new OutputData("lib/build/classes/java/jmh", "lib", "classes-jmh"),
            new OutputData("app/build/libs/app.jar", "app", LatheLayout.CLASSES_DIR),
            new OutputData("app/build/classes/java/test", "app", LatheLayout.TEST_CLASSES_DIR),
            new OutputData("app/build/classes/java/it", "app", "classes-it"),
            new OutputData("dup/target/classes", "dup", LatheLayout.CLASSES_DIR),
            new OutputData("dup/target/classes", "dup", LatheLayout.TEST_CLASSES_DIR)));

    final ReactorRewrite rewrite = WorkspaceManifest.load(tmp).reactorRewrite();

    assertThat(rewrite.expand(libJar))
        .containsExactly(lib.resolve(LatheLayout.CLASSES_DIR), lib.resolve("classes-gen"));
    assertThat(rewrite.expand(appJar)).containsExactly(app.resolve(LatheLayout.CLASSES_DIR));
    assertThat(rewrite.expand(tmp.resolve("dup/target/classes")))
        .containsExactly(tmp.resolve(".lathe/dup/classes"));
  }

  // A shaded jar holds classes it relocated, in packages no mirror of its module has: analysis
  // takes the mirror, with the module's own classes, and then the jar; a launch the jar alone,
  // whose
  // own classes name the relocated packages. A jar holding only its module's packages is not kept.
  @Test
  void load_jarWithPackagesNoMirrorHas_analysisAddsItLaunchKeepsItAlone() throws Exception {
    final Path lib = tmp.resolve(".lathe/lib");
    final Path main = source("lib", "Main");
    TestJavac.compileToDir(lib.resolve(LatheLayout.CLASSES_DIR), main);
    final Path libJar = jar("lib", main, source("lib.shadow.dep", "Relocated"));
    final Path app = tmp.resolve(".lathe/app");
    final Path appMain = source("app", "App");
    TestJavac.compileToDir(app.resolve(LatheLayout.CLASSES_DIR), appMain);
    final Path appJar = jar("app", appMain);
    writeWorkspaceManifest(
        LatheLayout.SCHEMA_VERSION,
        List.of(),
        null,
        List.of(),
        List.of(
            new OutputData("lib/build/libs/lib.jar", "lib", LatheLayout.CLASSES_DIR),
            new OutputData("app/build/libs/app.jar", "app", LatheLayout.CLASSES_DIR)));

    final ReactorRewrite rewrite = WorkspaceManifest.load(tmp).reactorRewrite();

    assertThat(rewrite.expand(libJar))
        .containsExactly(lib.resolve(LatheLayout.CLASSES_DIR), libJar);
    assertThat(rewrite.toLatheClassPath(List.of(libJar.toString())))
        .containsExactly(libJar.toString());
    assertThat(rewrite.expand(appJar)).containsExactly(app.resolve(LatheLayout.CLASSES_DIR));
  }

  private Path source(final String pkg, final String name) throws Exception {
    return TestCompiler.writeAt(
        tmp.resolve("src/%s/%s.java".formatted(pkg, name)),
        "package %s; public class %s {}".formatted(pkg, name),
        1_000L);
  }

  // <module>/build/libs/<module>.jar holding the sources' classes.
  private Path jar(final String module, final Path... sources) throws Exception {
    final Path jar = tmp.resolve("%s/build/libs/%s.jar".formatted(module, module));
    Files.createDirectories(jar.getParent());
    TestJavac.compileToJar(jar, tmp.resolve("jar-" + module), List.of(), sources);
    return jar;
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
