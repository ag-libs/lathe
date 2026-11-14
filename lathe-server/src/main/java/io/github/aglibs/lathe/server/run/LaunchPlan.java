package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.launch.ReactorRewrite;
import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.schema.LaunchMode;
import io.github.aglibs.lathe.core.schema.MainLaunchData;
import io.github.aglibs.lathe.core.schema.TestLaunchData;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class LaunchPlan {

  private LaunchPlan() {}

  public static List<String> forTest(
      final TestLaunchData data,
      final ReactorRewrite reactorRewrite,
      final List<Path> runnerClasspath,
      final List<TestSelection> selections,
      final Path resultsSink,
      final LaunchOverlay overlay,
      final JdwpOptions jdwp) {
    final var args =
        jvm(
            testRuntime(data, reactorRewrite, runnerClasspath),
            overlay,
            jdwp,
            List.of("-D%s=%s".formatted(LatheFlags.RESULTS_SINK, resultsSink)));
    args.add(LatheLayout.TEST_RUNNER_MAIN_CLASS);
    selections.forEach(selection -> args.addAll(selection.toRunnerArgs()));
    args.addAll(overlay.programArgs());
    return List.copyOf(args);
  }

  public static List<String> forMain(
      final MainLaunchData data,
      final ReactorRewrite reactorRewrite,
      final String mainClass,
      final LaunchOverlay overlay,
      final JdwpOptions jdwp) {
    final var args = jvm(mainRuntime(data, reactorRewrite), overlay, jdwp, List.of());
    addMainEntry(args, data.mode(), data.mainModule(), mainClass, overlay);
    return List.copyOf(args);
  }

  /**
   * A {@code main} class that lives in a module's test sources: it runs against the module's
   * captured test launch template (which patches the test-classes into the module and carries the
   * test-scope dependency graph), with the user's class as the entry point instead of the JUnit
   * runner — the only faithful way to run a test-scope {@code main}, since its class is not on the
   * main module path. Differs from {@link #forTest} only in the entry point (no runner, no
   * selections, no results sink).
   */
  public static List<String> forTestMain(
      final TestLaunchData data,
      final ReactorRewrite reactorRewrite,
      final String mainClass,
      final LaunchOverlay overlay,
      final JdwpOptions jdwp) {
    final var args = jvm(testRuntime(data, reactorRewrite, List.of()), overlay, jdwp, List.of());
    addMainEntry(args, data.mode(), data.mainModule(), mainClass, overlay);
    return List.copyOf(args);
  }

  // What the JVM runs with, whichever template it comes from, its paths rewritten to the mirror.
  private record Runtime(
      String javaHome,
      List<String> jvmArgs,
      List<String> modulePath,
      List<String> classPath,
      Map<String, String> patchModules,
      List<String> addOpens,
      List<String> addReads,
      List<String> addExports,
      List<String> addModules) {}

  // extraClasspath: what the launch adds after the template's own class path (the test runner).
  private static Runtime testRuntime(
      final TestLaunchData data,
      final ReactorRewrite reactorRewrite,
      final List<Path> extraClasspath) {
    final Map<String, String> patchModules = reactorRewrite.toLathe(data.patchModules());
    return new Runtime(
        data.javaHome(),
        data.jvmArgs(),
        reactorRewrite.toLathe(data.modulePath()),
        reactorRewrite.toLathe(
            Stream.concat(data.classPath().stream(), extraClasspath.stream().map(Path::toString))
                .toList()),
        patchModules,
        completeAddOpens(patchModules, data.addOpens()),
        data.addReads(),
        data.addExports(),
        data.addModules());
  }

  private static Runtime mainRuntime(
      final MainLaunchData data, final ReactorRewrite reactorRewrite) {
    return new Runtime(
        data.javaHome(),
        data.jvmArgs(),
        reactorRewrite.toLathe(data.modulePath()),
        reactorRewrite.toLathe(data.classPath()),
        Map.of(),
        data.addOpens(),
        data.addReads(),
        data.addExports(),
        data.addModules());
  }

  // The java command up to its entry point: the JVM, its options, the debug agent, the launch's own
  // JVM arguments, then the module and class paths with the overlay's appended.
  private static ArrayList<String> jvm(
      final Runtime runtime,
      final LaunchOverlay overlay,
      final JdwpOptions jdwp,
      final List<String> launchJvmArgs) {
    final var args = new ArrayList<String>();
    args.add(Path.of(runtime.javaHome(), "bin", "java").toString());
    // First, so the build's and the user's own JVM arguments still override them.
    args.addAll(LatheFlags.UTF8_OUTPUT_JVM_ARGS);
    args.addAll(runtime.jvmArgs());
    args.addAll(overlay.jvmArgs());
    if (jdwp.enabled()) {
      args.add(jdwp.agentArg());
    }

    args.addAll(launchJvmArgs);
    addPathOption(args, "--module-path", append(runtime.modulePath(), overlay.modulePathAppend()));
    addPathOption(args, "--class-path", append(runtime.classPath(), overlay.classpathAppend()));
    addPatchModules(args, runtime.patchModules());
    addRepeatedOption(args, "--add-opens", runtime.addOpens());
    addRepeatedOption(args, "--add-reads", runtime.addReads());
    addRepeatedOption(args, "--add-exports", runtime.addExports());
    addRepeatedOption(args, "--add-modules", runtime.addModules());
    return args;
  }

  private static void addMainEntry(
      final List<String> args,
      final LaunchMode mode,
      final String mainModule,
      final String mainClass,
      final LaunchOverlay overlay) {
    if (mode == LaunchMode.MODULE) {
      args.add("-m");
      args.add("%s/%s".formatted(mainModule, mainClass));
    } else {
      args.add(mainClass);
    }

    args.addAll(overlay.programArgs());
  }

  private static List<String> append(final List<String> base, final List<String> extra) {
    return Stream.concat(base.stream(), extra.stream()).toList();
  }

  private static void addPathOption(
      final List<String> args, final String option, final List<String> paths) {
    if (paths.isEmpty()) {
      return;
    }

    args.add(option);
    args.add(String.join(File.pathSeparator, paths));
  }

  private static void addPatchModules(
      final List<String> args, final Map<String, String> patchModules) {
    for (final Map.Entry<String, String> entry : patchModules.entrySet()) {
      args.add("--patch-module");
      args.add("%s=%s".formatted(entry.getKey(), entry.getValue()));
    }
  }

  private static void addRepeatedOption(
      final List<String> args, final String option, final List<String> values) {
    for (final String value : values) {
      args.add(option);
      args.add(value);
    }
  }

  // Captured --add-opens covers only the packages the capturing Surefire fork ran; a package added
  // after capture is missing entirely. Derive the complete set at replay from each patched module's
  // compiled test-classes (kept fresh on save), keeping captured opens that target other modules
  // (JDK/dependency opens the scan cannot see) verbatim.
  private static List<String> completeAddOpens(
      final Map<String, String> patchModules, final List<String> capturedOpens) {
    if (patchModules.isEmpty()) {
      return capturedOpens;
    }

    try {
      final var derived = new ArrayList<String>();
      for (final var entry : patchModules.entrySet()) {
        for (final var pkg : FileUtil.packagesWithClasses(Path.of(entry.getValue()))) {
          derived.add("%s/%s=ALL-UNNAMED".formatted(entry.getKey(), pkg));
        }
      }

      if (derived.isEmpty()) {
        return capturedOpens;
      }

      final var merged = new LinkedHashSet<>(derived);
      for (final String open : capturedOpens) {
        if (!targetsPatchedModule(open, patchModules)) {
          merged.add(open);
        }
      }

      return merged.stream().sorted().toList();
    } catch (final IOException | RuntimeException e) {
      return capturedOpens;
    }
  }

  private static boolean targetsPatchedModule(
      final String open, final Map<String, String> patchModules) {
    final int slash = open.indexOf('/');
    return slash > 0 && patchModules.containsKey(open.substring(0, slash));
  }
}
