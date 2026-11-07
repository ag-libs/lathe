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
  // beside its classes, as its jar does, since replay stands the mirror in for the jar. A project
  // without Java sources has no mirror, so its outputs are not listed: replay takes its jar. Of two
  // libraries from a Maven repository, the one with a -sources jar is recorded as present and its
  // sources extracted into Lathe's cache, the other as missing, resolved under a reused
  // configuration cache, with a jar one of the build's own tasks makes on the classpath.
  @Test
  void sync_multiProjectBuild_writesTheWorkspaceFilesAndInstallsTheServer() throws Exception {
    final var fixture =
        multiProject()
            .file("settings.gradle", SETTINGS + "include(\"res\")\n")
            .file("res/build.gradle", "plugins { id 'java-library' }\n")
            .file("res/src/main/resources/res.properties", "name=res\n")
            .file("lib/src/main/resources/META-INF/services/com.example.Api", "lib\n")
            .mavenLibrary("repo", "withsrc", true)
            .mavenLibrary("repo", "nosrc", false)
            .file(
                "app/build.gradle",
                """
                plugins { id 'java' }
                repositories { maven { url = uri("$rootDir/repo") } }
                def extraJar = tasks.register('extraJar', Jar) {
                  archiveFileName = 'extra.jar'
                  destinationDirectory = layout.buildDirectory.dir('extra')
                }
                dependencies {
                  implementation project(':lib')
                  implementation 'com.example:withsrc:1.0'
                  testImplementation 'com.example:nosrc:1.0'
                  testImplementation files(extraJar)
                }
                """);

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
    assertThat(workspace.outputs()).extracting(OutputData::module).doesNotContain("res");
    assertThat(workspace.resourceRoots())
        .contains(
            new ResourceRootData(
                "lib/src/main/resources", "lib/build/resources/main", "", false, "lib"));
    assertThat(workspace.buildFiles())
        .extracting(BuildFileData::path)
        .contains("settings.gradle", "lib/build.gradle", "app/build.gradle");
    assertThat(Path.of(Files.readString(lathe.resolve(LatheLayout.JAVA_HOME_FILE)))).isDirectory();
    assertThat(lathe.resolve(LatheLayout.LAUNCHER_SCRIPT)).exists();
    assertThat(Files.readString(lathe.resolve(LatheLayout.SYNC_SCRIPT), StandardCharsets.UTF_8))
        .contains("latheSync", "--module", "-Plathe.capture.only=true --continue");
    assertThat(lathe.resolve("app/classes/com/example/app/App.class")).exists();
    assertThat(lathe.resolve("lib/classes/META-INF/services/com.example.Api")).exists();
    final MainLaunchData launch =
        Json.read(lathe.resolve("app").resolve(LatheLayout.MAIN_LAUNCH_FILE), MainLaunchData.class);
    assertThat(launch.mode()).isEqualTo(LaunchMode.CLASSPATH);
    assertThat(launch.workingDir()).isEqualTo("app");
    assertThat(launch.classPath())
        .contains(
            fixture.path("app/build/classes/java/main"), fixture.path("lib/build/libs/lib.jar"));
    final List<DependencyData> libraries = workspace.dependencySources();
    assertThat(libraries)
        .extracting(DependencyData::gav, DependencyData::status)
        .containsExactlyInAnyOrder(
            tuple("com.example:nosrc:1.0", SourceStatus.MISSING),
            tuple("com.example:withsrc:1.0", SourceStatus.PRESENT));
    final DependencyData withSources =
        libraries.stream().filter(library -> library.dir() != null).findFirst().orElseThrow();
    assertThat(Path.of(withSources.dir()).resolve("com/example/withsrc/Api.java")).exists();
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
                "app/build.gradle",
                """
                plugins { id 'java' }
                dependencies { implementation project(':lib') }
                compileJava {
                  options.fork = true
                  options.forkOptions.jvmArgs = ['--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED']
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
            .file("settings.gradle", SETTINGS + "include(\"tool\", \"gen\")\n")
            .file("tool/build.gradle", GradleFixture.JAVA)
            .file(
                "tool/src/main/java/com/example/tool/Tool.java",
                "package com.example.tool; class Tool {}\n")
            .file(
                "gen/build.gradle",
                """
                plugins { id 'java' }
                def generate = tasks.register('generate') {
                  def out = layout.buildDirectory.dir('generated/gen')
                  outputs.dir(out)
                  doLast {
                    def file = out.get().file('com/example/gen/Gen.java').asFile
                    file.parentFile.mkdirs()
                    file.text = 'package com.example.gen; public class Gen {}'
                  }
                }
                sourceSets.main.java.srcDir(generate)
                """);
    fixture.runner("latheSync", "--configuration-cache").build();
    fixture.file("settings.gradle", SETTINGS + "include(\"gen\")\n");
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
                "build.gradle",
                """
                def lock = layout.projectDirectory.file('.lathe/lathe.lock').asFile
                tasks.register('probeLock') {
                  mustRunAfter(':app:latheCaptureMain')
                  doLast { println('LOCK HELD ' + lock.exists()) }
                }
                """);
    fixture.runner("latheSync").build();

    final var result = fixture.runner("latheSync", "probeLock").build();

    assertThat(result.getOutput()).contains("LOCK HELD true");
    assertThat(LatheLayout.reactorLockFile(dir.resolve(LatheLayout.LATHE_DIR))).doesNotExist();
  }

  // A plain build keeps .lathe/ current, as a Maven build does, with no sync: the workspace files,
  // and each test run's launch, captured in the test JVM while the tests run. lathe-junit enters as
  // an agent through the environment, so a build cache filled without Lathe still serves the test
  // task (which then forks nothing and captures nothing); a real run captures; and the editor's
  // capture-only sync captures without running the tests, a JUnit @Suite included (JUnit's dry run
  // discovers its tests, then skips them). Lathe's dependencies are added only as its
  // configurations resolve, so a script walking every configuration's dependencies neither resolves
  // one early nor removes Lathe's.
  @Test
  void build_plainBuild_refreshesTheWorkspaceAndCapturesTestRuns() throws Exception {
    final var fixture =
        multiProject()
            .file(
                "settings.gradle",
                SETTINGS
                    + "include(\"app:agent\")\n"
                    + "buildCache { local { directory = file(\"build-cache\") } }\n")
            .file("app/agent/build.gradle", GradleFixture.JAVA)
            .file(
                "app/agent/src/main/java/com/example/agent/Agent.java",
                "package com.example.agent; class Agent {}\n")
            .file(
                "app/build.gradle",
                """
                plugins { id 'java' }
                dependencies {
                  implementation project(':lib')
                  testImplementation platform('org.junit:junit-bom:5.11.4')
                  testImplementation 'org.junit.jupiter:junit-jupiter'
                  testImplementation 'org.junit.platform:junit-platform-suite'
                  testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
                  testCompileOnly 'com.example:unused:1.0'
                }
                test { useJUnitPlatform() }
                // As Micronaut's build does: walk every configuration's dependencies, then change one.
                configurations.all { dependencies.removeAll { it.group == 'io.github.ag-libs' } }
                configurations.testCompileOnly { dependencies.removeAll { it.name == 'unused' } }
                """)
            .file(
                "app/src/test/java/com/example/app/AppSuite.java",
                """
                package com.example.app;

                @org.junit.platform.suite.api.Suite
                @org.junit.platform.suite.api.SelectClasses(AppTest.class)
                class AppSuite {}
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
    final var captureOnly = fixture.runner("latheSync", "-Plathe.capture.only=true").build();

    assertThat(cached.task(":app:test").getOutcome()).isEqualTo(TaskOutcome.FROM_CACHE);
    assertThat(captureOnly.getOutput())
        .doesNotContain("captured no test launch", "captured a launch naming");
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

  // A project whose directory is outside the root build's has no place under .lathe/ (its module
  // would be ../shared, and .lathe/../shared a directory of the build): it is left out. The fixture
  // keeps the Kotlin DSL, so the workspace records Kotlin build files as the server's build files.
  @Test
  void sync_projectOutsideTheRoot_leftOutAndWritesNothingThere() throws Exception {
    final Path root = dir.resolve("root");
    final var fixture =
        new GradleFixture(root)
            .file(
                "settings.gradle.kts",
                "%sinclude(\"app\", \"shared\")\nproject(\":shared\").projectDir = file(\"../shared\")\n%s"
                    .formatted(
                        GradleFixture.latheSettings("root"), GradleFixture.latheRepositories()))
            .file("build.gradle.kts", "")
            .file("../shared/build.gradle.kts", "plugins { `java-library` }\n")
            .file(
                "../shared/src/main/java/com/example/shared/Shared.java",
                "package com.example.shared; public class Shared {}\n")
            .file(
                "app/build.gradle.kts",
                "plugins { java }\ndependencies { implementation(project(\":shared\")) }\n")
            .file(
                "app/src/main/java/com/example/app/App.java",
                "package com.example.app; class App { com.example.shared.Shared shared; }\n");

    fixture.runner("latheSync").build();

    final WorkspaceManifestData workspace =
        Json.read(
            root.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_JSON),
            WorkspaceManifestData.class);
    assertThat(root.resolve("shared")).doesNotExist();
    assertThat(root.resolve(".lathe/app/classes/com/example/app/App.class")).exists();
    assertThat(workspace.outputs()).extracting(OutputData::module).containsOnly("app");
    assertThat(workspace.buildFiles())
        .extracting(BuildFileData::path)
        .contains("settings.gradle.kts", "build.gradle.kts", "app/build.gradle.kts");
  }

  // Without lathe-junit the capture-only filter is missing too: a capture-only sync skips the test
  // task, and says why, rather than run every test. Here app's own repositories lack it, as a
  // project that declares its repositories can.
  @Test
  void captureOnlySync_latheJunitUnresolvable_skipsTheTestsAndSaysWhy() throws Exception {
    final var fixture =
        multiProject()
            .file(
                "app/build.gradle",
                """
                plugins { id 'java' }
                repositories { mavenCentral() }
                dependencies {
                  implementation project(':lib')
                  testImplementation platform('org.junit:junit-bom:5.11.4')
                  testImplementation 'org.junit.jupiter:junit-jupiter'
                  testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
                }
                test { useJUnitPlatform() }
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

    final var result = fixture.runner("latheSync", "-Plathe.capture.only=true").build();

    assertThat(result.task(":app:test").getOutcome()).isEqualTo(TaskOutcome.SKIPPED);
    assertThat(result.getOutput())
        .contains("[lathe] :app:test skipped: lathe-junit could not be resolved");
    assertThat(dir.resolve("app/ran.txt")).doesNotExist();
    assertThat(dir.resolve(".lathe/app").resolve(LatheLayout.TEST_LAUNCH_FILE)).doesNotExist();
  }

  // A build can fail a test task whose tests were all skipped, as a capture-only run skips them.
  // The facts come before the tests, as on Maven, so the first sync still writes the workspace and
  // the test JVM still captures its launch; only the build's own check fails.
  @Test
  void captureOnlySync_testTaskFails_stillWritesTheWorkspaceAndCaptures() throws Exception {
    final var result =
        failingTests().runner("latheSync", "-Plathe.capture.only=true").buildAndFail();

    assertThat(result.task(":app:test").getOutcome()).isEqualTo(TaskOutcome.FAILED);
    assertThat(result.getOutput()).contains("every test was skipped");
    assertThat(workspace().outputs()).extracting(OutputData::module).contains("app", "lib");
    assertThat(workspace().runnerClasspath()).isNotEmpty();
    assertThat(dir.resolve(".lathe/app").resolve(LatheLayout.TEST_LAUNCH_FILE)).exists();
  }

  // Under Isolated Projects the ordering is still allowed: on Gradle 9 the test tasks wait for the
  // root's workspace; on Gradle 8, where a project may not name another's task, only for their own
  // project's facts, so only the failing project's own module is certain to be written.
  @Test
  void captureOnlySync_testTaskFailsUnderIsolatedProjects_stillWritesItsModule() throws Exception {
    final var result =
        failingTests()
            .runner(
                "latheSync",
                "-Plathe.capture.only=true",
                "-Dorg.gradle.unsafe.isolated-projects=true")
            .buildAndFail();

    assertThat(result.getOutput()).contains("every test was skipped");
    assertThat(dir.resolve(".lathe/app").resolve("lathe-facts.json")).exists();
    assertThat(dir.resolve(".lathe/app").resolve(LatheLayout.TEST_LAUNCH_FILE)).exists();
  }

  // app's tests pass, then its test task fails, as a build that rejects skipped tests does.
  private GradleFixture failingTests() throws Exception {
    return multiProject()
        .file(
            "app/build.gradle",
            appTests("doLast { throw new GradleException(\"every test was skipped\") }"))
        .file(
            "app/src/test/java/com/example/app/AppTest.java",
            """
            package com.example.app;

            class AppTest {
              @org.junit.jupiter.api.Test
              void runs() {}
            }
            """);
  }

  // Develocity's test acceleration runs the tests in a JVM of its own, without the task's
  // JDK_JAVA_OPTIONS: lathe-junit is not loaded, so nothing is captured and the tests run in full,
  // and the sync says so rather than leave the editor with no launch and no reason.
  @Test
  void captureOnlySync_testJvmWithoutTheAgent_warnsThatNothingWasCaptured() throws Exception {
    final var fixture =
        multiProject()
            .file(
                "app/build.gradle",
                appTests("doFirst { environment.remove(\"JDK_JAVA_OPTIONS\") }"))
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

    final var result = fixture.runner("latheSync", "-Plathe.capture.only=true").build();

    assertThat(result.getOutput())
        .contains("[lathe] :app:test captured no test launch: its test JVM did not load");
    assertThat(dir.resolve("app/ran.txt")).exists();
    assertThat(dir.resolve(".lathe/app").resolve(LatheLayout.TEST_LAUNCH_FILE)).doesNotExist();
  }

  // A capture-only sync runs one class, the first in name order, which starts one test JVM however
  // many forks the build allows: the launch is the same whichever classes run. That class has no
  // tests and the build's own filter excludes it, and it still starts the JVM. Another test task
  // the test task depends on is skipped, as its launch is not captured. A plain test run keeps the
  // build's selection and runs every test, the other task's too.
  @Test
  void captureOnlySync_manyTestClassesAndForks_runsOneClassInOneTestJvm() throws Exception {
    final var fixture =
        multiProject()
            .file(
                "app/build.gradle",
                appTests(
                        """
                    maxParallelForks = 4
                    filter { excludeTestsMatching '*AFixture' }
                    beforeSuite { suite -> if (suite.name.startsWith('Gradle Test Executor')) {
                      println "[fork] ${suite.name}"
                    } }
                    """)
                    + """
                    def other = tasks.register('otherTest', Test) {
                      testClassesDirs = sourceSets.test.output.classesDirs
                      classpath = sourceSets.test.runtimeClasspath
                      useJUnitPlatform()
                    }
                    test.dependsOn other
                    """)
            .file(
                "app/src/test/java/com/example/app/AFixture.java",
                "package com.example.app;\n\nclass AFixture {}\n");
    for (final String name : List.of("BTest", "CTest", "DTest")) {
      fixture.file(
          "app/src/test/java/com/example/app/%s.java".formatted(name),
          """
          package com.example.app;

          class %s {
            @org.junit.jupiter.api.Test
            void runs() throws Exception {
              java.nio.file.Files.writeString(java.nio.file.Path.of("%s.ran"), "ran");
            }
          }
          """
              .formatted(name, name));
    }

    final var capture = fixture.runner("latheSync", "-Plathe.capture.only=true").build();

    assertThat(capture.getOutput().lines().filter(line -> line.startsWith("[fork] "))).hasSize(1);
    assertThat(dir.resolve(".lathe/app").resolve(LatheLayout.TEST_LAUNCH_FILE)).exists();
    assertThat(dir.resolve("app/build/test-results/test/TEST-com.example.app.BTest.xml"))
        .doesNotExist();
    assertThat(capture.task(":app:otherTest").getOutcome()).isEqualTo(TaskOutcome.SKIPPED);
    assertThat(dir.resolve("app/BTest.ran")).doesNotExist();

    final var plain = fixture.runner(":app:test").build();

    assertThat(plain.task(":app:otherTest").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);

    assertThat(dir.resolve("app/BTest.ran")).exists();
    assertThat(dir.resolve("app/CTest.ran")).exists();
    assertThat(dir.resolve("app/DTest.ran")).exists();
  }

  private static String appTests(final String testConfiguration) {
    return """
        plugins { id 'java' }
        dependencies {
          implementation project(':lib')
          testImplementation platform('org.junit:junit-bom:5.11.4')
          testImplementation 'org.junit.jupiter:junit-jupiter'
          testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
        }
        test {
          useJUnitPlatform()
          %s
        }
        """
        .formatted(testConfiguration);
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
        .file("settings.gradle", SETTINGS)
        .file("build.gradle", "")
        .file("lib/build.gradle", "plugins { id 'java-library' }\n")
        .file(
            "lib/src/main/java/com/example/lib/Lib.java",
            "package com.example.lib; public class Lib {}\n")
        .file("lib/src/main/resources/lib.properties", "name=lib\n")
        .file(
            "app/build.gradle",
            GradleFixture.JAVA + "dependencies { implementation project(':lib') }\n")
        .file(
            "app/src/main/java/com/example/app/App.java",
            "package com.example.app; class App { com.example.lib.Lib lib; }\n")
        .file(
            "app/src/test/java/com/example/app/AppTest.java",
            "package com.example.app; class AppTest {}\n");
  }
}
