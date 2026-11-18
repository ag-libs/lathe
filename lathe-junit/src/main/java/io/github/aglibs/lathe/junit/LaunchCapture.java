package io.github.aglibs.lathe.junit;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheWorkspace;
import io.github.aglibs.lathe.core.schema.LaunchMode;
import io.github.aglibs.lathe.core.schema.TestLaunchData;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

final class LaunchCapture {

  // What Gradle's test worker adds to a test JVM's launch, not the build: its own jar (on the
  // class or module path), its -D properties, and, on a modular run, itself as the main module.
  private static final String TEST_WORKER_JAR = "gradle-worker.jar";
  private static final String TEST_WORKER_PROPERTY_PREFIX = "-Dorg.gradle.";
  private static final String TEST_WORKER_MAIN_MODULE = "-Djdk.module.main=gradle.worker";

  private static final String CAPTURE_ONLY_PROPERTY = "-D%s=".formatted(LatheFlags.CAPTURE_ONLY);

  private static final String KIND = "junit-platform";
  private static final String AGENT_OPTION = "-javaagent:";

  private LaunchCapture() {}

  // Writes this test JVM's launch to its module's test-launch.json, when the module is inside a
  // Lathe workspace.
  static void capture() throws Exception {
    // What findRoot checks for a build: a build that turns Lathe off (lathe.disabled, or on CI)
    // leaves .lathe/ as it is.
    if (LatheFlags.isDisabled()) {
      return;
    }

    final Optional<CaptureSite> site =
        site(
            Path.of("").toAbsolutePath().normalize(), System.getenv(LatheFlags.CAPTURE_MODULE_ENV));
    if (site.isEmpty()) {
      return;
    }

    final Path moduleDir =
        site.get().root().resolve(LatheLayout.LATHE_DIR).resolve(site.get().moduleRel());
    final TestLaunchData data =
        toLaunchData(
            System.getProperty(LatheLayout.JAVA_HOME_PROPERTY),
            System.getProperty("java.class.path", ""),
            ManagementFactory.getRuntimeMXBean().getInputArguments(),
            ownJarLocation().orElse(null),
            FileUtil.slashed(site.get().workingRel()));
    final Optional<String> launchFile =
        launchFile(moduleDir, System.getenv(LatheFlags.CAPTURE_TREE_ENV));
    if (launchFile.isEmpty()) {
      return;
    }

    Files.createDirectories(moduleDir);
    FileUtil.writeAtomically(
        moduleDir, moduleDir.resolve(launchFile.get()), Json.toJson(data), false);
  }

  // Where a capture goes: the workspace root, the module, and the working directory a replay starts
  // in, relative to the root. The working directory stays the JVM's own, as the build ran the tests
  // there and they may read files from it.
  record CaptureSite(Path root, Path moduleRel, Path workingRel) {

    CaptureSite {
      ValidCheck.check()
          .notNull(root, "root")
          .notNull(moduleRel, "moduleRel")
          .notNull(workingRel, "workingRel")
          .validate();
    }
  }

  // The module is the one the build names when the tests run inside it (Lucene's build/tests-cwd),
  // else the working directory, as on Surefire. A name with the tests run outside the module is
  // inherited: a nested build the tests start (a TestKit fixture in a temporary directory) gets the
  // test JVM's environment, and must not write its launch over the module's.
  static Optional<CaptureSite> site(final Path workingDir, final String namedModule) {
    final Path named =
        namedModule == null || namedModule.isBlank()
            ? workingDir
            : Path.of(namedModule).toAbsolutePath().normalize();
    final Path moduleDir = workingDir.startsWith(named) ? named : workingDir;
    return LatheWorkspace.nearestRoot(moduleDir, null)
        .map(
            root -> new CaptureSite(root, root.relativize(moduleDir), root.relativize(workingDir)));
  }

  // The launch of the tree the build names (a Gradle test task of a source set beyond test), else
  // the module's test launch. A named tree the module does not have (a source set without Java
  // sources, or a name a nested build inherited from its parent's environment) gets none, rather
  // than the test launch another task's classpath would overwrite.
  static Optional<String> launchFile(final Path moduleDir, final String captureTree) {
    // The test tree's launch needs no params: a test tree of Groovy or Kotlin sources has none.
    if (captureTree == null
        || captureTree.isBlank()
        || LatheLayout.TEST_CLASSES_DIR.equals(captureTree)) {
      return Optional.of(LatheLayout.TEST_LAUNCH_FILE);
    }

    return Files.exists(moduleDir.resolve(LatheLayout.paramsFileName(captureTree)))
        ? Optional.of(LatheLayout.testLaunchFile(captureTree))
        : Optional.empty();
  }

  private static Optional<Path> ownJarLocation() throws URISyntaxException {
    final CodeSource codeSource = LaunchCapture.class.getProtectionDomain().getCodeSource();
    if (codeSource == null || codeSource.getLocation() == null) {
      return Optional.empty();
    }

    return Optional.of(Path.of(codeSource.getLocation().toURI()));
  }

  static TestLaunchData toLaunchData(
      final String javaHome,
      final String classPath,
      final List<String> inputArguments,
      final Path ownJarLocation,
      final String workingDir) {
    final var modulePath = new ArrayList<String>();
    final var patchModules = new LinkedHashMap<String, String>();
    final var addOpens = new ArrayList<String>();
    final var addReads = new ArrayList<String>();
    final var addExports = new ArrayList<String>();
    final var addModules = new ArrayList<String>();
    final var jvmArgs = new ArrayList<String>();

    for (int i = 0; i < inputArguments.size(); i++) {
      final String arg = inputArguments.get(i);
      final OptionValue modulePathOption = optionValue(arg, inputArguments, i, "--module-path");
      if (modulePathOption.present()) {
        modulePath.addAll(launchEntries(modulePathOption.value()));
        i += modulePathOption.consumedNext() ? 1 : 0;
        continue;
      }

      final OptionValue shortModulePath = optionValue(arg, inputArguments, i, "-p");
      if (shortModulePath.present()) {
        modulePath.addAll(launchEntries(shortModulePath.value()));
        i += shortModulePath.consumedNext() ? 1 : 0;
        continue;
      }

      final OptionValue patchModule = optionValue(arg, inputArguments, i, "--patch-module");
      if (patchModule.present()) {
        capturePatchModule(patchModules, patchModule.value());
        i += patchModule.consumedNext() ? 1 : 0;
        continue;
      }

      final OptionValue addOpensOption = optionValue(arg, inputArguments, i, "--add-opens");
      if (addOpensOption.present()) {
        addOpens.add(addOpensOption.value());
        i += addOpensOption.consumedNext() ? 1 : 0;
        continue;
      }

      final OptionValue addReadsOption = optionValue(arg, inputArguments, i, "--add-reads");
      if (addReadsOption.present()) {
        addReads.add(addReadsOption.value());
        i += addReadsOption.consumedNext() ? 1 : 0;
        continue;
      }

      final OptionValue addExportsOption = optionValue(arg, inputArguments, i, "--add-exports");
      if (addExportsOption.present()) {
        addExports.add(addExportsOption.value());
        i += addExportsOption.consumedNext() ? 1 : 0;
        continue;
      }

      final OptionValue addModulesOption = optionValue(arg, inputArguments, i, "--add-modules");
      if (addModulesOption.present()) {
        addModules.addAll(splitComma(addModulesOption.value()));
        i += addModulesOption.consumedNext() ? 1 : 0;
        continue;
      }

      if (!isBuildToolArgument(arg, ownJarLocation)) {
        jvmArgs.add(arg);
      }
    }

    final LaunchMode mode = patchModules.isEmpty() ? LaunchMode.CLASSPATH : LaunchMode.MODULE;
    final String mainModule = patchModules.isEmpty() ? "" : patchModules.keySet().iterator().next();
    return new TestLaunchData(
        KIND,
        mode,
        javaHome,
        mainModule,
        modulePath,
        capturedClassPath(classPath, ownJarLocation),
        patchModules,
        addOpens,
        addReads,
        addExports,
        addModules,
        jvmArgs,
        workingDir);
  }

  private static OptionValue optionValue(
      final String arg, final List<String> inputArguments, final int index, final String option) {
    final String prefix = option + "=";
    if (arg.startsWith(prefix)) {
      return new OptionValue(true, arg.substring(prefix.length()), false);
    }

    if (arg.equals(option) && index + 1 < inputArguments.size()) {
      return new OptionValue(true, inputArguments.get(index + 1), true);
    }

    return OptionValue.absent();
  }

  private static void capturePatchModule(
      final Map<String, String> patchModules, final String value) {
    final int separator = value.indexOf('=');
    if (separator < 1 || separator == value.length() - 1) {
      return;
    }

    patchModules.put(value.substring(0, separator), value.substring(separator + 1));
  }

  private static List<String> capturedClassPath(final String classPath, final Path ownJarLocation) {
    return launchEntries(classPath).stream()
        .filter(entry -> !samePath(entry, ownJarLocation))
        .toList();
  }

  // A class or module path without the build tool's own worker jar: replay starts its own runner.
  private static List<String> launchEntries(final String path) {
    return splitPath(path).stream()
        .filter(entry -> !TEST_WORKER_JAR.equals(fileName(entry)))
        .toList();
  }

  // An argument the build tool adds, not the build: Gradle's worker properties and main module,
  // this jar when the build loads it as an agent, and Lathe's capture-only switch, which a Gradle
  // sync sets on the test task and so reaches the command line: a replay runs the tests.
  private static boolean isBuildToolArgument(final String arg, final Path ownJarLocation) {
    return arg.startsWith(TEST_WORKER_PROPERTY_PREFIX)
        || arg.equals(TEST_WORKER_MAIN_MODULE)
        || arg.startsWith(CAPTURE_ONLY_PROPERTY)
        || (arg.startsWith(AGENT_OPTION) && samePath(agentJar(arg), ownJarLocation));
  }

  // -javaagent:<jar>[=<options>]
  private static String agentJar(final String arg) {
    final String value = arg.substring(AGENT_OPTION.length());
    final int options = value.indexOf('=');
    return options < 0 ? value : value.substring(0, options);
  }

  private static String fileName(final String entry) {
    final Path name = Path.of(entry).getFileName();
    return name != null ? name.toString() : "";
  }

  private static boolean samePath(final String entry, final Path ownJarLocation) {
    return ownJarLocation != null && Path.of(entry).normalize().equals(ownJarLocation.normalize());
  }

  // Each entry through Path, which collapses repeated separators: on Windows, Gradle passes the
  // worker's class path in an argfile with its backslashes escaped but unquoted, and the launcher
  // unescapes only inside quotes, so they arrive doubled.
  private static List<String> splitPath(final String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }

    return Stream.of(value.split(java.util.regex.Pattern.quote(File.pathSeparator)))
        .filter(entry -> !entry.isBlank())
        .map(entry -> Path.of(entry).toString())
        .toList();
  }

  private static List<String> splitComma(final String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }

    return Stream.of(value.split(",")).filter(entry -> !entry.isBlank()).toList();
  }

  private record OptionValue(boolean present, String value, boolean consumedNext) {

    private OptionValue {}

    private static OptionValue absent() {
      return new OptionValue(false, "", false);
    }
  }
}
