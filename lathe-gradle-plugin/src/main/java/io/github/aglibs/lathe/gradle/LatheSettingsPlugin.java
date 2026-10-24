package io.github.aglibs.lathe.gradle;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import org.gradle.api.GradleException;
import org.gradle.api.IsolatedAction;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.initialization.ProjectDescriptor;
import org.gradle.api.initialization.Settings;
import org.gradle.api.initialization.resolve.DependencyResolutionManagement;
import org.gradle.api.initialization.resolve.RepositoriesMode;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.util.GradleVersion;

// Registered in settings.gradle(.kts) or by an init script; applies Lathe to every project unless
// the build turns it off, and stops a build older than Lathe supports before any project is
// configured.
public class LatheSettingsPlugin implements Plugin<Settings> {

  private static final String ROOT_PATH = ":";

  // Only the root build: an init script reaches buildSrc and every included build too, and each of
  // those would write a .lathe/ of its own.
  @Override
  public void apply(final Settings settings) {
    if (settings.getGradle().getParent() != null || isDisabled(settings)) {
      return;
    }

    final List<String> unmet = unmet(GradleVersion.current());
    if (!unmet.isEmpty()) {
      throw new GradleException(LatheFlags.unsupportedBuild(unmet, "-P"));
    }

    // Providers, read when Gradle isolates the action, after the settings file has run:
    // plugins {} applies this plugin before its include(...) lines, an init script after them.
    final ProviderFactory providers = settings.getProviders();
    settings
        .getGradle()
        .getLifecycle()
        .beforeProject(
            new ApplyLathe(
                providers.provider(() -> modulePaths(settings)),
                providers.provider(() -> addMavenCentral(settings))));
  }

  // Project directory, relative to the root, to project path: the sync's --module takes the
  // directory, the name .lathe/ gives the module.
  private static HashMap<String, String> modulePaths(final Settings settings) {
    final Path root = settings.getRootDir().toPath();
    final var paths = new HashMap<String, String>();
    final var pending = new ArrayDeque<ProjectDescriptor>(List.of(settings.getRootProject()));
    while (!pending.isEmpty()) {
      final ProjectDescriptor project = pending.pop();
      paths.put(
          FileUtil.slashed(root.relativize(project.getProjectDir().toPath())), project.getPath());
      pending.addAll(project.getChildren());
    }

    return paths;
  }

  // The server is resolved through the root project, so it needs a repository; add Maven Central
  // only when the build declares none the root would see.
  private static boolean addMavenCentral(final Settings settings) {
    final DependencyResolutionManagement resolution = settings.getDependencyResolutionManagement();
    return resolution.getRepositories().isEmpty()
        && resolution.getRepositoriesMode().getOrElse(RepositoriesMode.PREFER_PROJECT)
            == RepositoriesMode.PREFER_PROJECT;
  }

  // As on Maven: -Plathe.disabled and CI are absolute; lathe.disabled=true in gradle.properties
  // opts the repository out unless the developer opted back in by creating .lathe/.
  private static boolean isDisabled(final Settings settings) {
    final ProviderFactory providers = settings.getProviders();
    final String commandLine =
        settings.getStartParameter().getProjectProperties().get(LatheFlags.DISABLED);
    if (LatheFlags.isDisabled(commandLine, providers.environmentVariable("CI").isPresent())) {
      return true;
    }

    return "true".equals(providers.gradleProperty(LatheFlags.DISABLED).getOrNull())
        && !Files.isDirectory(settings.getRootDir().toPath().resolve(LatheLayout.LATHE_DIR));
  }

  static List<String> unmet(final GradleVersion version) {
    final var minimum = GradleVersion.version(LatheLayout.Gradle.MIN_GRADLE_VERSION);
    if (version.getBaseVersion().compareTo(minimum) >= 0) {
      return List.of();
    }

    return List.of(
        "Gradle %s is older than %2$s, the oldest Lathe supports: upgrade Gradle, or the project's wrapper (./gradlew wrapper --gradle-version %2$s)"
            .formatted(version.getVersion(), minimum.getVersion()));
  }

  // A class holding plain values, not a lambda: Gradle isolates the action per project, so it must
  // hold no reference to the build that registered it. A HashMap, not an immutable copy: Gradle 8.8
  // cannot restore a JDK immutable collection from isolated state.
  private static final class ApplyLathe implements IsolatedAction<Project> {

    private final Provider<HashMap<String, String>> modulePaths;
    private final Provider<Boolean> addMavenCentral;

    private ApplyLathe(
        final Provider<HashMap<String, String>> modulePaths,
        final Provider<Boolean> addMavenCentral) {
      this.modulePaths = modulePaths;
      this.addMavenCentral = addMavenCentral;
    }

    @Override
    public void execute(final Project project) {
      project.getPluginManager().apply(LatheProjectPlugin.class);
      if (ROOT_PATH.equals(project.getPath())) {
        LatheProjectPlugin.registerSync(project, modulePaths.get(), addMavenCentral.get());
      }
    }
  }
}
