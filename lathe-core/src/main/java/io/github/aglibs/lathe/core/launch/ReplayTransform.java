package io.github.aglibs.lathe.core.launch;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.LaunchMode;
import io.github.aglibs.lathe.core.schema.MainLaunchData;
import io.github.aglibs.lathe.core.schema.TestLaunchData;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class ReplayTransform {

  private ReplayTransform() {}

  public static List<String> forTest(
      final TestLaunchData data,
      final Path workspaceRoot,
      final Path runnerJar,
      final TestSelection selection) {
    final List<String> modulePath = ReactorRewrite.toLathe(data.modulePath(), workspaceRoot);
    final List<String> classPath =
        Stream.concat(data.classPath().stream(), Stream.of(runnerJar.toString())).toList();
    final List<String> rewrittenClassPath = ReactorRewrite.toLathe(classPath, workspaceRoot);
    final Map<String, String> patchModules =
        ReactorRewrite.toLathe(data.patchModules(), workspaceRoot);

    final var args = baseJavaArgs(data.javaHome(), data.jvmArgs());
    addRuntimeShape(
        args,
        modulePath,
        rewrittenClassPath,
        patchModules,
        data.addOpens(),
        data.addReads(),
        data.addExports(),
        data.addModules());
    args.add(LatheLayout.TEST_RUNNER_MAIN_CLASS);
    args.addAll(selection.toRunnerArgs());
    return List.copyOf(args);
  }

  public static List<String> forMain(
      final MainLaunchData data,
      final Path workspaceRoot,
      final String mainClass,
      final List<String> programArgs) {
    final List<String> modulePath = ReactorRewrite.toLathe(data.modulePath(), workspaceRoot);
    final List<String> classPath = ReactorRewrite.toLathe(data.classPath(), workspaceRoot);

    final var args = baseJavaArgs(data.javaHome(), data.jvmArgs());
    addRuntimeShape(
        args,
        modulePath,
        classPath,
        Map.of(),
        data.addOpens(),
        data.addReads(),
        data.addExports(),
        data.addModules());
    if (data.mode() == LaunchMode.MODULE) {
      args.add("-m");
      args.add("%s/%s".formatted(data.mainModule(), mainClass));
    } else {
      args.add(mainClass);
    }

    args.addAll(programArgs);
    return List.copyOf(args);
  }

  private static ArrayList<String> baseJavaArgs(final String javaHome, final List<String> jvmArgs) {
    final var args = new ArrayList<String>();
    args.add(Path.of(javaHome, "bin", "java").toString());
    args.addAll(jvmArgs);
    return args;
  }

  private static void addRuntimeShape(
      final List<String> args,
      final List<String> modulePath,
      final List<String> classPath,
      final Map<String, String> patchModules,
      final List<String> addOpens,
      final List<String> addReads,
      final List<String> addExports,
      final List<String> addModules) {
    addPathOption(args, "--module-path", modulePath);
    addPathOption(args, "--class-path", classPath);
    addPatchModules(args, patchModules);
    addRepeatedOption(args, "--add-opens", addOpens);
    addRepeatedOption(args, "--add-reads", addReads);
    addRepeatedOption(args, "--add-exports", addExports);
    addRepeatedOption(args, "--add-modules", addModules);
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
}
