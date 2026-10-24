package io.github.aglibs.lathe.gradle;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import java.nio.file.Files;
import java.util.List;
import org.gradle.api.GradleException;
import org.gradle.api.IsolatedAction;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.initialization.Settings;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.util.GradleVersion;

// Registered in settings.gradle(.kts) or by an init script; applies Lathe to every project unless
// the build turns it off, and stops a build older than Lathe supports before any project is
// configured.
public class LatheSettingsPlugin implements Plugin<Settings> {

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

    settings.getGradle().getLifecycle().beforeProject(new ApplyProjectPlugin());
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

  // A class, not a lambda: Gradle isolates the action per project, so it must hold no reference to
  // the build that registered it.
  private static final class ApplyProjectPlugin implements IsolatedAction<Project> {

    @Override
    public void execute(final Project project) {
      project.getPluginManager().apply(LatheProjectPlugin.class);
    }
  }
}
