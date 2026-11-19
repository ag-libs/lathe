package io.github.aglibs.lathe.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.install.jdk.JdkSourceResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LatheWorkspaceTaskTest {

  @TempDir Path tmp;

  // The server runs on the newest compiling JDK it can run on; a build that compiles only on older
  // JDKs (a toolchain on 11) gets the JDK the build itself runs on.
  @Test
  void compilingJdk_toolchainsOlderThanTheServer_leftOut() throws IOException {
    final ProjectFacts on11 = facts("lib", jdk("jdk-11", "11"), 11, List.of());
    final Path jdk21 = jdk("jdk-21", "21");

    assertThat(
            LatheWorkspaceTask.compilingJdk(List.of(on11, facts("app", jdk21, 21, List.of())))
                .home())
        .isEqualTo(jdk21.toRealPath());
    assertThat(LatheWorkspaceTask.compilingJdk(List.of(on11)).home())
        .isEqualTo(JdkSourceResolver.resolve().home());
  }

  // A project this build did not refresh keeps its last facts: a runner from a build since deleted
  // is passed over, and named, for the first one still in place; none in place means no runner.
  @Test
  void runnerClasspath_staleRunnerFirst_takesThePresentOneAndNamesTheStale() throws IOException {
    final Path javaHome = jdk("jdk-21", "21");
    final String present = Files.writeString(tmp.resolve("runner.jar"), "jar").toString();
    final String gone = tmp.resolve("deleted/runner.jar").toString();
    final var stale = new ArrayList<String>();

    final List<String> runner =
        LatheWorkspaceTask.runnerClasspath(
            List.of(
                facts("api", javaHome, 21, List.of()),
                facts("app", javaHome, 21, List.of(gone)),
                facts("lib", javaHome, 21, List.of(present))),
            stale::add);

    assertThat(runner).containsExactly(present);
    assertThat(stale).containsExactly("app");
    assertThat(
            LatheWorkspaceTask.runnerClasspath(
                List.of(facts("app", javaHome, 21, List.of(gone))), module -> {}))
        .isEmpty();
  }

  // TE-21: each project's runner goes under its launcher's version, its JUnit platform's, so a
  // build on JUnit 5 and 6 at once replays each module on its own; a runner since deleted is left
  // out, as for the single runner.
  @Test
  void runnerClasspaths_projectsOnJUnit5And6_eachRunnerByItsLauncherVersion() throws IOException {
    final Path javaHome = jdk("jdk-21", "21");
    final String runner = Files.writeString(tmp.resolve("runner.jar"), "jar").toString();
    final String launcher5 =
        Files.writeString(tmp.resolve("junit-platform-launcher-1.14.4.jar"), "jar").toString();
    final String launcher6 =
        Files.writeString(tmp.resolve("junit-platform-launcher-6.1.3.jar"), "jar").toString();
    final String gone = tmp.resolve("deleted/junit-platform-launcher-1.13.0.jar").toString();

    assertThat(
            LatheWorkspaceTask.runnerClasspaths(
                List.of(
                    facts("old", javaHome, 21, List.of(runner, launcher5)),
                    facts("new", javaHome, 21, List.of(runner, launcher6)),
                    facts("gone", javaHome, 21, List.of(runner, gone)))))
        .containsOnly(
            Map.entry("1.14.4", List.of(runner, launcher5)),
            Map.entry("6.1.3", List.of(runner, launcher6)));
  }

  private Path jdk(final String name, final String version) throws IOException {
    final Path home = Files.createDirectories(tmp.resolve(name));
    Files.writeString(home.resolve("release"), "JAVA_VERSION=\"%s\"%n".formatted(version));
    return home;
  }

  private static ProjectFacts facts(
      final String module, final Path javaHome, final int version, final List<String> runner) {
    return new ProjectFacts(
        module,
        List.of(),
        List.of(),
        javaHome.toString(),
        version,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        runner,
        false);
  }
}
