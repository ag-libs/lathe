package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.Platform;
import io.github.aglibs.lathe.install.SyncException;
import io.github.aglibs.lathe.install.SyncScript;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// Maven's .lathe/lathe-sync.sh, the one command clients, the server, and the MCP server run to
// refresh .lathe/, so none of them compose a Maven command line. It runs the Maven and JDK that ran
// this build, and prints the exact command first so the user sees what runs.
final class MavenSyncScript {

  static final String SYNC_PHASE = "process-test-classes";
  private static final String TEST_PHASE = "test";
  static final String PACKAGE_PHASE = "package";
  // The phases a project may have its sync build to (LatheFlags.SYNC_PHASE), in lifecycle order:
  // from SYNC_PHASE, which .lathe/ needs, to install; never deploy, which would publish.
  static final List<String> SYNC_PHASES =
      List.of(
          SYNC_PHASE,
          TEST_PHASE,
          "prepare-package",
          PACKAGE_PHASE,
          "pre-integration-test",
          "integration-test",
          "post-integration-test",
          "verify",
          "install");
  private static final String SKIP_TESTS_ARG = "-DskipTests";
  // Failsafe's own switch: a capture-only sync past the test phase captures Surefire's tests
  // only.
  private static final String SKIP_ITS_ARG = "-DskipITs";
  // A personal activation: Lathe brought in from the command line, not by the project, with
  // compile errors kept from stopping the build before lathe:sync.
  static final String EXT_CLASS_PATH = "maven.ext.class.path";
  static final String FAIL_ON_ERROR = "maven.compiler.failOnError";

  private MavenSyncScript() {}

  // The phase the project chose (LatheFlags.SYNC_PHASE): a -D on the build or in
  // .mvn/maven.config, else the root POM's property, else process-test-classes. A phase before it
  // could not refresh .lathe/, and deploy would publish, so either stops the build.
  static String phase(final Properties userProperties, final Properties rootProjectProperties) {
    final String phase =
        userProperties
            .getProperty(
                LatheFlags.SYNC_PHASE,
                rootProjectProperties.getProperty(LatheFlags.SYNC_PHASE, SYNC_PHASE))
            .trim();
    if (!SYNC_PHASES.contains(phase)) {
      throw new SyncException(
          "%s=%s: Lathe's sync builds to one of %s"
              .formatted(LatheFlags.SYNC_PHASE, phase, SYNC_PHASES));
    }

    return phase;
  }

  // --tests captures each module's test launch without running the tests: the capture-only filter
  // excludes them all, and failIfNoTests=false keeps a build that requires tests green; past the
  // test phase it runs to the project's phase, skipping Failsafe's integration tests, which Lathe
  // does not capture. A plain sync that reaches the test phase skips the tests, Failsafe's too.
  // A scoped sync builds the modules with -am, so they resolve against fresh upstream classes. No
  // -amd: Maven does not add the dependents' own upstream, so on a large reactor a dependent fails
  // to resolve a module outside the scope; callers that need dependents name them. The build cache
  // stays off, since a cache hit skips javac and the test fork, where Lathe captures.
  // A personal activation (its jars, from activation) is replayed and its jars required.
  static SyncScript of(
      final Properties systemProperties,
      final Properties userProperties,
      final String phase,
      final List<Path> activation) {
    final int position = SYNC_PHASES.indexOf(phase);
    final int testPosition = SYNC_PHASES.indexOf(TEST_PHASE);
    return new SyncScript(
        LatheLayout.Maven.runningTool(systemProperties, Platform.WINDOWS),
        Path.of(systemProperties.getProperty(LatheLayout.JAVA_HOME_PROPERTY)),
        activation,
        Stream.concat(
                Stream.of(
                    LatheLayout.Maven.NO_TRANSFER_PROGRESS, "-Dmaven.build.cache.enabled=false"),
                activationArgs(activation, userProperties))
            .toList(),
        position >= testPosition ? List.of(phase, SKIP_TESTS_ARG) : List.of(phase),
        position > testPosition
            ? List.of(
                phase, LatheLayout.Maven.CAPTURE_ONLY_ARG, "-DfailIfNoTests=false", SKIP_ITS_ARG)
            : List.of(TEST_PHASE, LatheLayout.Maven.CAPTURE_ONLY_ARG, "-DfailIfNoTests=false"),
        List.of("-pl", LatheFlags.SYNC_MODULE_TOKEN),
        List.of("-am"));
  }

  // The -Dmaven.ext.class.path entries when they bring Lathe in (one is lathe-maven-extension's
  // jar), else none: another extension brought in that way is not Lathe's to replay. Resolved as
  // Maven does, against the directory the build started in, since the sync script runs from the
  // workspace root.
  static List<Path> activation(final Properties userProperties, final Path invocationDir) {
    final List<Path> entries =
        Stream.of(userProperties.getProperty(EXT_CLASS_PATH, "").split(File.pathSeparator))
            .filter(entry -> !entry.isBlank())
            .map(entry -> invocationDir.resolve(entry.trim()).normalize())
            .toList();
    return entries.stream().anyMatch(MavenSyncScript::isLatheExtensionJar) ? entries : List.of();
  }

  private static boolean isLatheExtensionJar(final Path entry) {
    return entry
            .getFileName()
            .toString()
            .startsWith("%s-".formatted(LatheLayout.Maven.EXTENSION_ARTIFACT_ID))
        && FileUtil.isJarFile(entry);
  }

  private static Stream<String> activationArgs(
      final List<Path> activation, final Properties userProperties) {
    if (activation.isEmpty()) {
      return Stream.empty();
    }

    final String classPath =
        activation.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
    final String failOnError = userProperties.getProperty(FAIL_ON_ERROR);
    return Stream.concat(
        Stream.of("-D%s=%s".formatted(EXT_CLASS_PATH, classPath)),
        failOnError == null
            ? Stream.empty()
            : Stream.of("-D%s=%s".formatted(FAIL_ON_ERROR, failOnError.trim())));
  }
}
