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

  private static final String KIND_SUREFIRE = "surefire";

  private LaunchCapture() {}

  static TestLaunchData toLaunchData(
      final String javaHome,
      final String classPath,
      final List<String> inputArguments,
      final Path ownJarLocation) {
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
        modulePath.addAll(splitPath(modulePathOption.value()));
        i += modulePathOption.consumedNext() ? 1 : 0;
        continue;
      }

      final OptionValue shortModulePath = optionValue(arg, inputArguments, i, "-p");
      if (shortModulePath.present()) {
        modulePath.addAll(splitPath(shortModulePath.value()));
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

      jvmArgs.add(arg);
    }

    final LaunchMode mode = patchModules.isEmpty() ? LaunchMode.CLASSPATH : LaunchMode.MODULE;
    final String mainModule = patchModules.isEmpty() ? "" : patchModules.keySet().iterator().next();
    return new TestLaunchData(
        LatheLayout.TEST_LAUNCH_SCHEMA_VERSION,
        KIND_SUREFIRE,
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
        jvmArgs);
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
    return splitPath(classPath).stream().filter(entry -> !samePath(entry, ownJarLocation)).toList();
  }

  private static boolean samePath(final String entry, final Path ownJarLocation) {
    return ownJarLocation != null && Path.of(entry).normalize().equals(ownJarLocation.normalize());
  }

  private static List<String> splitPath(final String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }

    return Stream.of(value.split(java.util.regex.Pattern.quote(File.pathSeparator)))
        .filter(entry -> !entry.isBlank())
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
