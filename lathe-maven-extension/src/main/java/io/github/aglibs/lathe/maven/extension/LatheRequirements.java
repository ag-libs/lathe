package io.github.aglibs.lathe.maven.extension;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

import io.github.aglibs.lathe.core.LatheLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;

/** What Lathe needs from a build: each unmet requirement, worded as what is wrong and the fix. */
final class LatheRequirements {

  private LatheRequirements() {}

  static List<String> unmet(final String mavenVersion, final List<MavenProject> projects) {
    final var unmet = new ArrayList<String>();
    if (mavenVersion != null && isOlder(mavenVersion, LatheLayout.Maven.MIN_MAVEN_VERSION)) {
      unmet.add(
          "Maven %s is older than %s, the oldest Lathe supports: upgrade Maven, or the project's wrapper (mvn wrapper:wrapper -Dmaven=3.9.11)"
              .formatted(mavenVersion, LatheLayout.Maven.MIN_MAVEN_VERSION));
    }

    final Map<String, List<String>> modulesByOldCompiler =
        projects.stream()
            .flatMap(
                project -> oldCompilerVersion(project).map(v -> Map.entry(v, project)).stream())
            .collect(
                groupingBy(
                    Map.Entry::getKey,
                    TreeMap::new,
                    mapping(entry -> entry.getValue().getArtifactId(), toList())));
    modulesByOldCompiler.forEach(
        (version, modules) ->
            unmet.add(
                "maven-compiler-plugin %s (%s) is older than %s: pin a newer version in <pluginManagement>, for example 3.13.0"
                    .formatted(
                        version,
                        String.join(", ", modules),
                        LatheLayout.Maven.MIN_COMPILER_PLUGIN_VERSION)));
    return List.copyOf(unmet);
  }

  private static Optional<String> oldCompilerVersion(final MavenProject project) {
    return LatheModelInjector.compilerPlugin(project)
        .map(Plugin::getVersion)
        .filter(version -> isOlder(version, LatheLayout.Maven.MIN_COMPILER_PLUGIN_VERSION));
  }

  private static boolean isOlder(final String version, final String minimum) {
    return new ComparableVersion(version).compareTo(new ComparableVersion(minimum)) < 0;
  }
}
