package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.launch.JdwpOptions;
import io.github.aglibs.lathe.core.launch.LaunchOverlay;
import io.github.aglibs.lathe.core.launch.LaunchPlan;
import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.schema.MainLaunchData;
import io.github.aglibs.lathe.core.schema.TestLaunchData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Applies a resolved {@link RunItem} onto a captured or derived launch template, producing the
 * final {@link ResolvedLaunch}. Append-paths and the working directory are resolved relative to the
 * workspace root (absolute paths pass through unchanged); the overlay never touches
 * launch-correctness fields.
 */
public final class RunOverlay {

  private static final Logger LOG = Logger.getLogger(RunOverlay.class.getName());

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
    return new ResolvedLaunch(
        argv,
        resolveEnv(item, workspaceRoot),
        resolveCwd(item, workspaceRoot, template.workingDir()));
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
    return new ResolvedLaunch(
        argv,
        resolveEnv(item, workspaceRoot),
        resolveCwd(item, workspaceRoot, template.workingDir()));
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
    return new ResolvedLaunch(
        argv,
        resolveEnv(item, workspaceRoot),
        resolveCwd(item, workspaceRoot, template.workingDir()));
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

  // The run's environment: an envFile (KEY=VALUE, workspace-root-relative) as the base, with the
  // inline env overriding it. A missing/unreadable envFile is ignored (fail-open), never a run
  // error.
  private static Map<String, String> resolveEnv(final RunItem item, final Path workspaceRoot) {
    if (item.envFile() == null) {
      return item.env();
    }

    final var merged = new LinkedHashMap<String, String>();
    merged.putAll(readEnvFile(workspaceRoot.resolve(item.envFile())));
    merged.putAll(item.env());
    return merged;
  }

  // A `.properties`-format env file (KEY=VALUE, `#`/`!` comments, backslash escaping) read as
  // UTF-8.
  private static Map<String, String> readEnvFile(final Path file) {
    if (!Files.exists(file)) {
      LOG.warning(() -> "[run-config] envFile not found, ignoring %s".formatted(file));
      return Map.of();
    }

    final var props = new Properties();
    try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      props.load(reader);
    } catch (final IOException e) {
      LOG.log(
          Level.WARNING, e, () -> "[run-config] envFile unreadable, ignoring %s".formatted(file));
      return Map.of();
    }

    return props.stringPropertyNames().stream()
        .collect(Collectors.toUnmodifiableMap(name -> name, props::getProperty));
  }

  private static Path resolveCwd(
      final RunItem item, final Path workspaceRoot, final String templateWorkingDir) {
    // An explicit overlay cwd wins; otherwise default to the module's working directory captured
    // in the launch template (the module basedir, matching Maven's Surefire/exec fork), so
    // relative-path resolution in tests/main matches `mvn`. An empty template working directory
    // means the reactor-root module, resolving to the workspace root.
    if (item.cwd() != null) {
      return workspaceRoot.resolve(item.cwd());
    }

    return workspaceRoot.resolve(templateWorkingDir);
  }
}
