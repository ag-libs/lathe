package io.github.aglibs.lathe.gradle;

import io.github.aglibs.lathe.core.JavaArgfile;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.install.ServerInstaller;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.File;
import java.nio.file.Path;
import java.util.Objects;
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

  private LatheTestCapture() {}

  static void register(
      final Project project,
      final TaskProvider<LatheFactsTask> facts,
      final SourceSet test,
      final Path moduleDir,
      final boolean captureOnly) {
    final Configuration junit =
        LatheProjectPlugin.resolvable(
            project, LATHE_JUNIT, ServerInstaller.JUNIT_CAPTURE_COORDINATES);
    junit.setTransitive(false);
    final Path agentDir = moduleDir.resolve(LatheLayout.Gradle.AGENT_DIR);
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
                          agentDir.resolve(LatheLayout.Gradle.AGENT_JAR).toFile()));
                  if (captureOnly) {
                    captureOnly(task);
                  }
                });
    if (captureOnly) {
      facts.configure(
          task -> {
            task.dependsOn(testTask);
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
        task ->
            task.getRunnerClasspath()
                .set(
                    project.provider(
                        () -> runner.getFiles().stream().map(File::getPath).toList())));
  }

  // The editor's capture-only sync: the filter in lathe-junit skips every test, and the run always
  // forks, since a run served from the cache or up to date captures nothing.
  private static void captureOnly(final Test task) {
    task.systemProperty(LatheFlags.CAPTURE_ONLY, "true");
    task.getOutputs().upToDateWhen(ignored -> false);
    task.getOutputs().cacheIf(ignored -> false);
    if (GradleVersion.current().getBaseVersion().compareTo(FAIL_ON_NO_TESTS) >= 0) {
      task.getFailOnNoDiscoveredTests().set(false);
    }
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
