package io.github.aglibs.lathe.gradle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.BuildFileData;
import io.github.aglibs.lathe.core.schema.DependencyData;
import io.github.aglibs.lathe.core.schema.LaunchMode;
import io.github.aglibs.lathe.core.schema.MainLaunchData;
import io.github.aglibs.lathe.core.schema.OutputData;
import io.github.aglibs.lathe.core.schema.ResourceRootData;
import io.github.aglibs.lathe.core.schema.SourceStatus;
import io.github.aglibs.lathe.core.schema.TestLaunchData;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LatheSyncTaskFunctionalTest {

  private static final String SETTINGS =
      "%sinclude(\"lib\", \"app\")\n%s"
          .formatted(GradleFixture.latheSettings("root"), GradleFixture.latheRepositories());

  @TempDir Path dir;

  // A library and an app using it: latheSync writes the workspace files from both projects' facts,
  // installs the server, and is configuration-cache safe. The library's mirror holds its resources
  // beside its classes, as its jar does, since replay stands the mirror in for the jar.
  @Test
  void sync_multiProjectBuild_writesTheWorkspaceFilesAndInstallsTheServer() throws Exception {
    final var fixture =
        multiProject().file("lib/src/main/resources/META-INF/services/com.example.Api", "lib\n");

    fixture.runner("latheSync", "--configuration-cache").build();
    final var second = fixture.runner("latheSync", "--configuration-cache").build();

    assertThat(second.getOutput()).contains("Configuration cache entry reused.");
    final var lathe = dir.resolve(LatheLayout.LATHE_DIR);
    final WorkspaceManifestData workspace = workspace();
    assertThat(workspace.outputs())
        .contains(
            new OutputData("lib/build/classes/java/main", "lib", LatheLayout.CLASSES_DIR),
            new OutputData("lib/build/libs/lib.jar", "lib", LatheLayout.CLASSES_DIR),
            new OutputData("lib/build/resources/main", "lib", LatheLayout.CLASSES_DIR),
            new OutputData("app/build/classes/java/test", "app", LatheLayout.TEST_CLASSES_DIR));
    assertThat(workspace.resourceRoots())
        .contains(
            new ResourceRootData(
                "lib/src/main/resources", "lib/build/resources/main", "", false, "lib"));
    assertThat(workspace.buildFiles())
        .extracting(BuildFileData::path)
        .contains("settings.gradle.kts", "lib/build.gradle.kts", "app/build.gradle.kts");
    assertThat(Path.of(Files.readString(lathe.resolve(LatheLayout.JAVA_HOME_FILE)))).isDirectory();
    assertThat(lathe.resolve(LatheLayout.LAUNCHER_SCRIPT)).exists();
    assertThat(Files.readString(lathe.resolve(LatheLayout.SYNC_SCRIPT), StandardCharsets.UTF_8))
        .contains("latheSync", "--module");
    assertThat(lathe.resolve("app/classes/com/example/app/App.class")).exists();
    assertThat(lathe.resolve("lib/classes/META-INF/services/com.example.Api")).exists();
    final MainLaunchData launch =
        Json.read(lathe.resolve("app").resolve(LatheLayout.MAIN_LAUNCH_FILE), MainLaunchData.class);
    assertThat(launch.mode()).isEqualTo(LaunchMode.CLASSPATH);
    assertThat(launch.workingDir()).isEqualTo("app");
    assertThat(launch.classPath())
        .contains(
            fixture.path("app/build/classes/java/main"), fixture.path("lib/build/libs/lib.jar"));
  }

  // A one-module sync, like Maven's -pl, builds that module alone; the workspace files are written
  // from its fresh facts and whatever the other projects' last builds left (here, none).
  @Test
  void sync_oneModule_buildsItAloneAndWritesTheWorkspaceFromIt() throws Exception {
    final var fixture = multiProject();

    final var result = fixture.runner("latheSync", "--module", "lib").build();

    assertThat(result.task(":lib:latheCaptureMain")).isNotNull();
    assertThat(result.task(":app:compileJava")).isNull();
    assertThat(dir.resolve(".lathe/lib/classes/com/example/lib/Lib.class")).exists();
    assertThat(workspace().outputs())
        .extracting(OutputData::module)
        .contains("lib")
        .doesNotContain("app");
  }

  // Module-access flags a forked compile and the daemon run with reach the server's jvm.args.
  @Test
  void sync_forkedCompileWithAccessFlags_writesThemToJvmArgs() throws Exception {
    final var fixture =
        multiProject()
            .file(
                "app/build.gradle.kts",
                """
                plugins { java }
                dependencies { implementation(project(":lib")) }
                tasks.compileJava {
                  options.isFork = true
                  options.forkOptions.jvmArgs = listOf("--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED")
                }
                """)
            .file(
                "gradle.properties",
                "org.gradle.jvmargs=-Xmx512m --add-opens=java.base/java.lang=ALL-UNNAMED\n");

    fixture.runner("latheSync").build();

    assertThat(dir.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.JVM_ARGS_FILE))
        .content()
        .contains(
            "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED")
        .doesNotContain("-Xmx512m");
  }

  // Every build keeps .lathe/ to what the build has. A project dropped from the settings is pruned
  // by the next build, a one-project one too; a test tree whose sources are gone, once its
  // project's facts are refreshed; a source set generated by a task is kept, decided when the facts
  // task runs, after the generator, also under the configuration cache. A test compile builds no
  // jar for Lathe: the facts read the classpaths for their paths only.
  @Test
  void build_removedProjectAndTests_prunedAsTheBuildsSeeThem() throws Exception {
    final var fixture =
        multiProject()
            .file("settings.gradle.kts", SETTINGS + "include(\"tool\", \"gen\")\n")
            .file("tool/build.gradle.kts", GradleFixture.JAVA)
            .file(
                "tool/src/main/java/com/example/tool/Tool.java",
                "package com.example.tool; class Tool {}\n")
            .file(
                "gen/build.gradle.kts",
                """
                plugins { java }
                val generate = tasks.register("generate") {
                  val out = layout.buildDirectory.dir("generated/gen")
                  outputs.dir(out)
                  doLast {
                    val file = out.get().file("com/example/gen/Gen.java").asFile
                    file.parentFile.mkdirs()
                    file.writeText("package com.example.gen; public class Gen {}")
                  }
                }
                sourceSets.main { java.srcDir(generate) }
                """);
    fixture.runner("latheSync", "--configuration-cache").build();
    fixture.file("settings.gradle.kts", SETTINGS + "include(\"gen\")\n");
    FileUtil.deleteDir(dir.resolve("app/src/test"));

    fixture.runner(":lib:testClasses", "--configuration-cache").build();
    final boolean toolPrunedByLib = !Files.exists(params("tool", LatheLayout.CLASSES_DIR));
    final boolean appTestsKeptByLib = Files.exists(params("app", LatheLayout.TEST_CLASSES_DIR));
    final var testClasses = fixture.runner("testClasses", "--configuration-cache").build();

    assertThat(testClasses.task(":lib:jar")).isNull();
    assertThat(toolPrunedByLib).isTrue();
    assertThat(appTestsKeptByLib).isTrue();
    assertThat(params("tool", LatheLayout.CLASSES_DIR)).doesNotExist();
    assertThat(params("app", LatheLayout.TEST_CLASSES_DIR)).doesNotExist();
    assertThat(params("app", LatheLayout.CLASSES_DIR)).exists();
    assertThat(params("gen", LatheLayout.CLASSES_DIR)).exists();
    assertThat(params("lib", LatheLayout.CLASSES_DIR)).exists();
  }

  // The build lock is held while the build runs, from the first capture, and released at its end.
  // A first-ever build has no .lathe/ to lock, so the probe build is the second one.
  @Test
  void sync_secondBuild_holdsTheBuildLockUntilTheBuildEnds() throws Exception {
    final var fixture =
        multiProject()
            .file(
                "build.gradle.kts",
                """
                val lock = layout.projectDirectory.file(".lathe/lathe.lock").asFile
                tasks.register("probeLock") {
                  mustRunAfter(":app:latheCaptureMain")
                  doLast { println("LOCK HELD " + lock.exists()) }
                }
                """);
    fixture.runner("latheSync").build();

    final var result = fixture.runner("latheSync", "probeLock").build();

    assertThat(result.getOutput()).contains("LOCK HELD true");
    assertThat(LatheLayout.reactorLockFile(dir.resolve(LatheLayout.LATHE_DIR))).doesNotExist();
  }

  // A library with a -sources jar and one without, from a Maven repository: the first is recorded
  // as present and its sources extracted into Lathe's cache, the second as missing; resolved under
  // a reused configuration cache, with a jar one of the build's own tasks makes on the classpath.
  @Test
  void sync_librariesWithAndWithoutSources_recordsThemAndExtractsTheSources() throws Exception {
    final var fixture =
        multiProject()
            .mavenLibrary("repo", "withsrc", true)
            .mavenLibrary("repo", "nosrc", false)
            .file(
                "app/build.gradle.kts",
                """
                plugins { java }
                repositories { maven { url = uri(rootDir.resolve("repo")) } }
                val extraJar = tasks.register<Jar>("extraJar") {
                  archiveFileName = "extra.jar"
                  destinationDirectory = layout.buildDirectory.dir("extra")
                }
                dependencies {
                  implementation(project(":lib"))
                  implementation("com.example:withsrc:1.0")
                  testImplementation("com.example:nosrc:1.0")
                  testImplementation(files(extraJar))
                }
                """);
    fixture.runner("latheSync", "--configuration-cache").build();

    final var second = fixture.runner("latheSync", "--configuration-cache").build();

    assertThat(second.getOutput()).contains("Configuration cache entry reused.");
    final List<DependencyData> libraries = workspace().dependencySources();
    assertThat(libraries)
        .extracting(DependencyData::gav, DependencyData::status)
        .containsExactlyInAnyOrder(
            tuple("com.example:nosrc:1.0", SourceStatus.MISSING),
            tuple("com.example:withsrc:1.0", SourceStatus.PRESENT));
    final DependencyData withSources =
        libraries.stream().filter(library -> library.dir() != null).findFirst().orElseThrow();
    assertThat(Path.of(withSources.dir()).resolve("com/example/withsrc/Api.java")).exists();
  }

  // A plain build keeps .lathe/ current, as a Maven build does, with no sync: the workspace files,
  // and each test run's launch, captured in the test JVM while the tests run. lathe-junit enters as
  // an agent through the environment, so a build cache filled without Lathe still serves the test
  // task (which then forks nothing and captures nothing); a real run captures; and the editor's
  // capture-only sync captures without running the tests. Lathe's dependencies are added only as
  // its configurations resolve, so a script walking every configuration's dependencies neither
  // resolves one early nor removes Lathe's.
  @Test
  void build_plainBuild_refreshesTheWorkspaceAndCapturesTestRuns() throws Exception {
    final var fixture =
        multiProject()
            .file(
                "settings.gradle.kts",
                SETTINGS
                    + "include(\"app:agent\")\n"
                    + "buildCache { local { directory = file(\"build-cache\") } }\n")
            .file("app/agent/build.gradle.kts", GradleFixture.JAVA)
            .file(
                "app/agent/src/main/java/com/example/agent/Agent.java",
                "package com.example.agent; class Agent {}\n")
            .file(
                "app/build.gradle.kts",
                """
                plugins { java }
                dependencies {
                  implementation(project(":lib"))
                  testImplementation(platform("org.junit:junit-bom:5.11.4"))
                  testImplementation("org.junit.jupiter:junit-jupiter")
                  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
                  testCompileOnly("com.example:unused:1.0")
                }
                tasks.test { useJUnitPlatform() }
                // As Micronaut's build does: walk every configuration's dependencies, then change one.
                configurations.all { dependencies.removeAll { it.group == "io.github.ag-libs" } }
                configurations.testCompileOnly { dependencies.removeAll { it.name == "unused" } }
                """)
            .file(
                "app/src/test/java/com/example/app/AppTest.java",
                """
                package com.example.app;

                class AppTest {
                  @org.junit.jupiter.api.Test
                  void runs() throws Exception {
                    java.nio.file.Files.writeString(java.nio.file.Path.of("ran.txt"), "ran");
                  }
                }
                """);
    final Path testLaunch = dir.resolve(".lathe/app").resolve(LatheLayout.TEST_LAUNCH_FILE);
    final Path ran = dir.resolve("app/ran.txt");
    fixture.runner("build", "--build-cache", "-Plathe.disabled=true").build();
    FileUtil.deleteDir(dir.resolve("app/build"));
    FileUtil.deleteDir(dir.resolve("lib/build"));

    final var cached = fixture.runner("build", "--build-cache").build();
    final boolean capturedFromCache = Files.exists(testLaunch);
    fixture.runner(":app:test", "--rerun", "--configuration-cache").build();
    final TestLaunchData launch = Json.read(testLaunch, TestLaunchData.class);
    Files.delete(testLaunch);
    Files.delete(ran);
    fixture.runner("latheSync", "-Plathe.capture.only=true").build();

    assertThat(cached.task(":app:test").getOutcome()).isEqualTo(TaskOutcome.FROM_CACHE);
    assertThat(capturedFromCache).isFalse();
    assertThat(dir.resolve(".lathe/app").resolve(LatheLayout.MAIN_LAUNCH_FILE)).exists();
    assertThat(launch.classPath())
        .contains(fixture.path("app/build/classes/java/test"))
        .noneMatch(entry -> entry.contains("gradle-worker") || entry.contains("lathe-junit"));
    assertThat(launch.jvmArgs()).noneMatch(arg -> arg.startsWith("-javaagent"));
    // The launcher is on the test runtime already, which replay starts from.
    assertThat(workspace().runnerClasspath())
        .singleElement()
        .asString()
        .contains("lathe-test-runner");
    assertThat(testLaunch).exists();
    assertThat(ran).doesNotExist();
    // The agent's copy into .lathe/app/ leaves the child project in app/agent/ alone.
    assertThat(dir.resolve(".lathe/app/.agent/lathe-junit.jar")).exists();
    assertThat(dir.resolve(".lathe/app/agent/classes/com/example/agent/Agent.class")).exists();
  }

  private WorkspaceManifestData workspace() throws IOException {
    return Json.read(
        dir.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_JSON),
        WorkspaceManifestData.class);
  }

  private Path params(final String module, final String tree) {
    return dir.resolve(LatheLayout.LATHE_DIR)
        .resolve(module)
        .resolve(LatheLayout.paramsFileName(tree));
  }

  private GradleFixture multiProject() throws Exception {
    return new GradleFixture(dir)
        .file("settings.gradle.kts", SETTINGS)
        .file("build.gradle.kts", "")
        .file("lib/build.gradle.kts", "plugins { `java-library` }\n")
        .file(
            "lib/src/main/java/com/example/lib/Lib.java",
            "package com.example.lib; public class Lib {}\n")
        .file("lib/src/main/resources/lib.properties", "name=lib\n")
        .file(
            "app/build.gradle.kts",
            GradleFixture.JAVA + "dependencies { implementation(project(\":lib\")) }\n")
        .file(
            "app/src/main/java/com/example/app/App.java",
            "package com.example.app; class App { com.example.lib.Lib lib; }\n")
        .file(
            "app/src/test/java/com/example/app/AppTest.java",
            "package com.example.app; class AppTest {}\n");
  }
}
