package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.launch.JdwpOptions;
import io.github.aglibs.lathe.core.launch.LaunchOverlay;
import io.github.aglibs.lathe.core.launch.LaunchPlan;
import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.schema.MainLaunchData;
import io.github.aglibs.lathe.core.schema.TestLaunchData;
import java.nio.file.Path;
import java.util.List;

/**
 * Applies a resolved {@link RunItem} onto a captured or derived launch template, producing the
 * final {@link ResolvedLaunch}. Append-paths and the working directory are resolved relative to the
 * workspace root (absolute paths pass through unchanged); the overlay never touches
 * launch-correctness fields.
 */
public final class RunOverlay {

  private RunOverlay() {}

  public static ResolvedLaunch applyToMain(
      final MainLaunchData template,
      final Path workspaceRoot,
      final String mainClass,
      final RunItem item,
      final JdwpOptions jdwp) {
    final List<String> argv =
        LaunchPlan.forMain(
            template, workspaceRoot, mainClass, launchOverlay(item, workspaceRoot), jdwp);
    return new ResolvedLaunch(argv, item.env(), resolveCwd(item, workspaceRoot));
  }

  public static ResolvedLaunch applyToTestMain(
      final TestLaunchData template,
      final Path workspaceRoot,
      final String mainClass,
      final RunItem item,
      final JdwpOptions jdwp) {
    final List<String> argv =
        LaunchPlan.forTestMain(
            template, workspaceRoot, mainClass, launchOverlay(item, workspaceRoot), jdwp);
    return new ResolvedLaunch(argv, item.env(), resolveCwd(item, workspaceRoot));
  }

  public static ResolvedLaunch applyToTest(
      final TestLaunchData template,
      final Path workspaceRoot,
      final List<Path> runnerClasspath,
      final List<TestSelection> selections,
      final Path resultsSink,
      final RunItem item,
      final JdwpOptions jdwp) {
    final List<String> argv =
        LaunchPlan.forTest(
            template,
            workspaceRoot,
            runnerClasspath,
            selections,
            resultsSink,
            launchOverlay(item, workspaceRoot),
            jdwp);
    return new ResolvedLaunch(argv, item.env(), resolveCwd(item, workspaceRoot));
  }

  private static LaunchOverlay launchOverlay(final RunItem item, final Path workspaceRoot) {
    return new LaunchOverlay(
        item.jvmArgs(),
        item.args(),
        resolvePaths(item.classpathAppend(), workspaceRoot),
        resolvePaths(item.modulePathAppend(), workspaceRoot));
  }

  private static List<String> resolvePaths(final List<String> entries, final Path workspaceRoot) {
    return entries.stream().map(entry -> workspaceRoot.resolve(entry).toString()).toList();
  }

  private static Path resolveCwd(final RunItem item, final Path workspaceRoot) {
    if (item.cwd() == null) {
      return null;
    }

    return workspaceRoot.resolve(item.cwd());
  }
}
