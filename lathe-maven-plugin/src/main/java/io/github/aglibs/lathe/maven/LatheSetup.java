package io.github.aglibs.lathe.maven;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

final class LatheSetup {

  private static final String GROUP_ID = "io.github.ag-libs";
  private static final String COMPILER_ARTIFACT_ID = "lathe-compiler";
  private static final String MAVEN_PLUGIN_ARTIFACT_ID = "lathe-maven-plugin";
  private static final String COMPILER_PLUGIN_GROUP_ID = "org.apache.maven.plugins";
  private static final String COMPILER_PLUGIN_ARTIFACT_ID = "maven-compiler-plugin";

  private LatheSetup() {}

  static Report inspect(final MavenProject topLevelProject, final List<MavenProject> projects) {
    final List<Plugin> plugins =
        projects.stream().flatMap(project -> plugins(project).stream()).toList();
    final List<Plugin> topLevelPlugins = plugins(topLevelProject);
    final var compilerPlugin =
        findPlugin(plugins, COMPILER_PLUGIN_GROUP_ID, COMPILER_PLUGIN_ARTIFACT_ID);
    final var compilerDependency =
        compilerPlugin != null ? latheCompilerDependency(compilerPlugin) : null;
    final var mavenPlugin = findPlugin(topLevelPlugins, GROUP_ID, MAVEN_PLUGIN_ARTIFACT_ID);
    return new Report(
        compilerDependency != null,
        compilerPlugin != null && hasCompilerId(compilerPlugin),
        mavenPlugin != null && hasSyncExecution(mavenPlugin),
        compilerDependency != null ? compilerDependency.getVersion() : null,
        mavenPlugin != null ? mavenPlugin.getVersion() : null);
  }

  private static List<Plugin> plugins(final MavenProject project) {
    final var build = project.getBuild();
    if (build == null) {
      return List.of();
    }

    final var buildPlugins = build.getPlugins();
    final var managedPlugins =
        build.getPluginManagement() != null
            ? build.getPluginManagement().getPlugins()
            : List.<Plugin>of();
    return Stream.concat(buildPlugins.stream(), managedPlugins.stream()).toList();
  }

  private static Plugin findPlugin(
      final List<Plugin> plugins, final String groupId, final String artifactId) {
    return plugins.stream()
        .filter(plugin -> groupId.equals(plugin.getGroupId()))
        .filter(plugin -> artifactId.equals(plugin.getArtifactId()))
        .findFirst()
        .orElse(null);
  }

  private static Dependency latheCompilerDependency(final Plugin plugin) {
    return plugin.getDependencies().stream()
        .filter(dependency -> GROUP_ID.equals(dependency.getGroupId()))
        .filter(dependency -> COMPILER_ARTIFACT_ID.equals(dependency.getArtifactId()))
        .findFirst()
        .orElse(null);
  }

  private static boolean hasCompilerId(final Plugin plugin) {
    if (!(plugin.getConfiguration() instanceof final Xpp3Dom config)) {
      return false;
    }
    final var compilerId = config.getChild("compilerId");
    return compilerId != null && "lathe".equals(compilerId.getValue());
  }

  private static boolean hasSyncExecution(final Plugin plugin) {
    return plugin.getExecutions().stream()
        .map(PluginExecution::getGoals)
        .filter(Objects::nonNull)
        .flatMap(List::stream)
        .anyMatch("sync"::equals);
  }

  record Report(
      boolean compilerDependencyConfigured,
      boolean compilerIdConfigured,
      boolean syncExecutionConfigured,
      String compilerVersion,
      String mavenPluginVersion) {

    boolean versionMismatch() {
      return compilerVersion != null
          && mavenPluginVersion != null
          && !compilerVersion.equals(mavenPluginVersion);
    }

    boolean complete() {
      return compilerDependencyConfigured && compilerIdConfigured && syncExecutionConfigured;
    }
  }
}
