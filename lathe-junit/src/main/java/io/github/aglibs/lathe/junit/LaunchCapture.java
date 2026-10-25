package io.github.aglibs.lathe.junit;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.LaunchMode;
import io.github.aglibs.lathe.core.schema.TestLaunchData;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

final class LaunchCapture {

  private static final String KIND = "junit-platform";
  private static final String AGENT_OPTION = "-javaagent:";

  private LaunchCapture() {}

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
        .filter(entry -> !LatheLayout.Gradle.TEST_WORKER_JAR.equals(fileName(entry)))
        .toList();
  }

  // An argument the build tool adds, not the build: Gradle's worker properties and main module,
  // and this jar when the build loads it as an agent.
  private static boolean isBuildToolArgument(final String arg, final Path ownJarLocation) {
    return arg.startsWith(LatheLayout.Gradle.TEST_WORKER_PROPERTY_PREFIX)
        || arg.equals(LatheLayout.Gradle.TEST_WORKER_MAIN_MODULE)
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
