package io.github.aglibs.lathe.gradle;

import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.JavaArgfile;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.TestLaunchData;
import io.github.aglibs.lathe.install.ServerInstaller;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;
import org.gradle.util.GradleVersion;

// Test capture, as on Maven: every run of a project's test task captures its launch from inside the
// test JVM, through lathe-junit's listener, while the tests run as usual. lathe-junit enters as an
// agent through the task's environment, which Gradle does not count as a task input, so the task's
// build-cache key stays the build's own. The editor's capture-only sync runs the tests without
// executing them.
final class LatheTestCapture {

  private static final String TEST_TASK = "test";
  private static final String JDK_JAVA_OPTIONS = "JDK_JAVA_OPTIONS";
  private static final String LATHE_JUNIT = "latheJunit";
  private static final String AGENT_TASK = "latheAgent";
  private static final String TEST_RUNNER = "latheTestRunner";
  private static final String JUNIT_LAUNCHER = "org.junit.platform:junit-platform-launcher";
  private static final GradleVersion FAIL_ON_NO_TESTS = GradleVersion.version("9.0");
  private static final String TEST_ACCELERATION =
      """
      A plugin that runs the tests in a JVM of its own, as Develocity's Predictive Test Selection \
      and Test Distribution do, keeps Lathe out: turn it off for the capture-only sync\
      """;

  private LatheTestCapture() {}

  static void register(
      final Project project,
      final TaskProvider<LatheFactsTask> facts,
      final SourceSet test,
      final Path moduleDir,
      final boolean captureOnly,
      final boolean crossProject) {
    final Configuration junit =
        LatheProjectPlugin.resolvable(
            project, LATHE_JUNIT, ServerInstaller.JUNIT_CAPTURE_COORDINATES);
    junit.setTransitive(false);
    final Path agentDir = moduleDir.resolve(LatheLayout.Gradle.AGENT_DIR);
    final File agentJar = agentDir.resolve(LatheLayout.Gradle.AGENT_JAR).toFile();
    final TaskProvider<Sync> agent =
        project
            .getTasks()
            .register(
                AGENT_TASK,
                Sync.class,
                task -> {
                  task.from(LatheProjectPlugin.lenient(junit));
                  task.into(agentDir.toFile());
                  task.rename(name -> LatheLayout.Gradle.AGENT_JAR);
                });
    final TaskProvider<Test> testTask =
        project
            .getTasks()
            .named(
                TEST_TASK,
                Test.class,
                task -> {
                  task.dependsOn(agent);
                  task.environment(
                      JDK_JAVA_OPTIONS,
                      new AgentOption(
                          Objects.toString(task.getEnvironment().get(JDK_JAVA_OPTIONS), ""),
                          agentJar));
                  if (captureOnly) {
                    captureOnly(
                        task,
                        agentJar,
                        moduleDir.toFile(),
                        project.getRootDir(),
                        project.getGradle().getGradleUserHomeDir());
                  }
                });
    // Every project's facts and the workspace come first and the tests follow, as on Maven, where
    // the sync runs before the test phase: Gradle starts no new task after a failure, so a failing
    // test task (a build that rejects skipped tests) would leave the workspace unwritten. Where a
    // project may not name another's task, only its own facts come first.
    if (captureOnly) {
      testTask.configure(
          task -> {
            task.mustRunAfter(facts);
            if (crossProject) {
              task.mustRunAfter(LatheProjectPlugin.ROOT_PATH + LatheProjectPlugin.WORKSPACE_TASK);
            }
          });
      facts.configure(
          task -> {
            task.finalizedBy(testTask);
            // Runs every capture-only sync, which is when a test task too old to capture warns.
            task.getOutputs().upToDateWhen(ignored -> false);
            task.getTestTask().set(testTask.map(Task::getPath));
            task.getTestJavaVersion()
                .set(
                    testTask
                        .flatMap(Test::getJavaLauncher)
                        .map(launcher -> launcher.getMetadata().getLanguageVersion().asInt()));
          });
    }

    // A plain Callable provider carries no task dependencies, so the paths are read, never built.
    final FileCollection runner = runnerClasspath(project, test);
    facts.configure(
        task -> task.getRunnerClasspath().set(project.provider(() -> GradleFiles.paths(runner))));
  }

  // The editor's capture-only sync: the filter in lathe-junit skips every test, and the run always
  // forks, since a run served from the cache or up to date captures nothing.
  // The task's actions hold only File values: the configuration cache on Gradle 8 can't restore a
  // List.of() a lambda captured.
  private static void captureOnly(
      final Test task,
      final File agentJar,
      final File moduleDir,
      final File rootDir,
      final File userHome) {
    task.systemProperty(LatheFlags.CAPTURE_ONLY, "true");
    task.onlyIf("lathe-junit is in place", test -> agentInPlace(test, agentJar));
    final var marker = new File(moduleDir, LatheLayout.Gradle.CAPTURE_MARKER);
    final var launch = new File(moduleDir, LatheLayout.TEST_LAUNCH_FILE);
    task.doFirst(test -> IOUtil.unchecked(() -> markCaptureStart(marker)));
    task.doLast(test -> warnIfNotCaptured(test, marker, launch, rootDir, userHome));
    task.getOutputs().upToDateWhen(ignored -> false);
    task.getOutputs().cacheIf(ignored -> false);
    if (GradleVersion.current().getBaseVersion().compareTo(FAIL_ON_NO_TESTS) >= 0) {
      task.getFailOnNoDiscoveredTests().set(false);
    }
  }

  private static void markCaptureStart(final File marker) throws IOException {
    Files.createDirectories(marker.toPath().getParent());
    Files.writeString(marker.toPath(), "", StandardCharsets.UTF_8);
  }

  // Develocity's test acceleration runs the tests in a JVM of its own, without the task's
  // JDK_JAVA_OPTIONS: lathe-junit is not loaded there, so the tests run in full, and either no
  // launch is captured, or the one captured comes from its test-selection JVM and names jars from
  // a temporary directory deleted later in the build. A launch written in the same clock tick as
  // the marker counts as written.
  private static void warnIfNotCaptured(
      final Task test,
      final File marker,
      final File launch,
      final File rootDir,
      final File userHome) {
    if (launch.lastModified() < marker.lastModified()) {
      test.getLogger()
          .warn(
              """
              [lathe] {} captured no test launch: its test JVM did not load lathe-junit, so its \
              tests ran in full. {}\
              """,
              test.getPath(),
              TEST_ACCELERATION);
      return;
    }

    temporaryJar(
            launch,
            List.of(rootDir.toPath(), userHome.toPath()),
            Path.of(System.getProperty("java.io.tmpdir")))
        .ifPresent(
            jar ->
                test.getLogger()
                    .warn(
                        """
                        [lathe] {} captured a launch naming {}, a temporary jar, so its tests \
                        can't be run from the editor. {}\
                        """,
                        test.getPath(),
                        jar,
                        TEST_ACCELERATION));
  }

  // A jar already gone, or one in the temporary directory outside the build and Gradle's user home
  // (either may live there, as on some CI machines): a launcher's copy, which outlives the test
  // task but not the build. A launch that can't be read is not checked: a warning must not fail
  // the build.
  static Optional<String> temporaryJar(
      final File launch, final List<Path> keptDirs, final Path tmpDir) {
    final TestLaunchData data;
    try {
      data = Json.read(launch.toPath(), TestLaunchData.class);
    } catch (final IOException | RuntimeException e) {
      return Optional.empty();
    }

    return Stream.concat(data.classPath().stream(), data.modulePath().stream())
        .filter(entry -> entry.endsWith(".jar"))
        .filter(entry -> isTemporary(Path.of(entry).normalize(), keptDirs, tmpDir))
        .findFirst();
  }

  private static boolean isTemporary(final Path jar, final List<Path> keptDirs, final Path tmpDir) {
    return !Files.exists(jar)
        || (jar.startsWith(tmpDir.normalize())
            && keptDirs.stream().noneMatch(dir -> jar.startsWith(dir.normalize())));
  }

  // Without lathe-junit the filter that skips the tests is missing too, and a capture-only run
  // would run every test while capturing nothing: the task is skipped instead, and says why.
  private static boolean agentInPlace(final Task test, final File agentJar) {
    if (agentJar.isFile()) {
      return true;
    }

    test.getLogger()
        .warn(
            "[lathe] {} skipped: lathe-junit could not be resolved, so its tests can't be captured",
            test.getPath());
    return false;
  }

  // The test runner and the JUnit launcher, beside the test runtime classpath that replay starts
  // from. The launcher's version comes from the test runtime: JUnit's own platform aligns it with
  // the project's JUnit. Lenient: a project without JUnit has no launcher to align, and no replay.
  private static FileCollection runnerClasspath(final Project project, final SourceSet test) {
    final Configuration testRuntime =
        project.getConfigurations().getByName(test.getRuntimeClasspathConfigurationName());
    final Configuration runner =
        LatheProjectPlugin.resolvable(
            project, TEST_RUNNER, ServerInstaller.TEST_RUNNER_COORDINATES, JUNIT_LAUNCHER);
    runner.extendsFrom(testRuntime);
    return LatheProjectPlugin.lenient(runner).minus(testRuntime);
  }

  // JDK_JAVA_OPTIONS for the test JVM: what the build already sets, then lathe-junit as an agent,
  // quoted as the launcher parses it (a Windows path's backslashes escaped). Gradle reads it as the
  // JVM starts, after latheAgent ran, and a jar latheAgent could not resolve is left out: a missing
  // agent stops the JVM from starting. Public: the configuration cache on Gradle 8 restores it
  // through its public constructor.
  public record AgentOption(String existing, File agentJar) {

    public AgentOption {
      ValidCheck.check().notNull(existing, "existing").notNull(agentJar, "agentJar").validate();
    }

    @Override
    public String toString() {
      return Stream.of(
              existing, agentJar.isFile() ? JavaArgfile.quote("-javaagent:" + agentJar) : "")
          .filter(option -> !option.isBlank())
          .collect(Collectors.joining(" "));
    }
  }
}
