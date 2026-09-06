package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.repository.RemoteRepository;

public final class ReactorProjects {

  private ReactorProjects() {}

  public static List<MavenProject> sorted(final MavenSession session, final Path workspaceRoot) {
    return session.getProjects().stream()
        .sorted(Comparator.comparing(project -> moduleRel(workspaceRoot, project)))
        .toList();
  }

  /**
   * True when this build's top-level project is the real reactor root, so {@code .lathe} belongs at
   * (and describes) the whole workspace. Only such a build may create {@code .lathe}; a {@code -pl}
   * or in-submodule build must never drop a stray one into a submodule (its editor root marker
   * would then resolve to the submodule and runnables discovery would crash). The class-refresh
   * path is unaffected -- the compiler still copies into an existing root {@code .lathe}
   * regardless.
   */
  public static boolean isReactorRootBuild(final MavenSession session) {
    return isReactorRoot(session.getTopLevelProject().getBasedir().toPath());
  }

  // A directory is the reactor root when no Maven project on disk aggregates it: no ancestor pom
  // declares a <module> that resolves onto it. This asks the poms directly rather than trusting the
  // .mvn-anchored multiModuleProjectDirectory (which a submodule build with no root .mvn/ spoofs to
  // its own directory) and, unlike a bare "any ancestor pom" check, does not false-skip a project
  // merely nested under an unrelated pom. Package-private and path-only so it is unit-testable
  // without a MavenSession; toRealPath resolves symlinks (basedir always exists during a build, so
  // a
  // failure means the build is already broken -- fail loud).
  static boolean isReactorRoot(final Path basedir) {
    return IOUtil.unchecked(
        () -> {
          final var candidate = basedir.toRealPath();
          var ancestor = candidate.getParent();
          while (ancestor != null) {
            if (aggregates(ancestor, candidate)) {
              return false;
            }

            ancestor = ancestor.getParent();
          }

          return true;
        });
  }

  // True when the pom in dir (if any) declares a <module> that resolves exactly to candidate --
  // i.e.
  // dir aggregates candidate, so candidate is a submodule and must not own a .lathe. Exact match,
  // not
  // startsWith: a <module> points at a child project's basedir, so a directory merely nested
  // *under*
  // a module (e.g. an IT fixture under lathe-maven-plugin/target) is not itself that module.
  private static boolean aggregates(final Path dir, final Path candidate) {
    final var pom = dir.resolve(LatheLayout.POM_XML);
    if (!Files.isRegularFile(pom)) {
      return false;
    }

    for (final String module : readModules(pom)) {
      if (candidate.equals(dir.resolve(module).normalize())) {
        return true;
      }
    }

    return false;
  }

  // Reads a pom's <modules> via Maven's own model reader. A missing or malformed pom cannot confirm
  // aggregation, so it is treated as non-aggregating (permissive -- better to create .lathe at a
  // genuine root than to wrongly suppress it over an unreadable ancestor pom).
  private static List<String> readModules(final Path pom) {
    try (final var in = Files.newInputStream(pom)) {
      return new MavenXpp3Reader().read(in).getModules();
    } catch (final IOException | XmlPullParserException e) {
      return List.of();
    }
  }

  /**
   * Returns a map from artifact key to the compile classpath of the first reactor module that has
   * that artifact. Used to populate per-dep classpath entries in the workspace manifest.
   *
   * <p>The classpath is the set of compile+provided-scope external JARs from that module. Maven has
   * already resolved version conflicts, so any module's view of a dep's transitive classpath is
   * correct.
   */
  public static Map<String, List<Path>> artifactClasspaths(final List<MavenProject> projects) {
    final var reactorGAs = reactorProjects(projects);
    final var result = new TreeMap<String, List<Path>>();
    for (final MavenProject project : projects) {
      final var projectClasspath =
          project.getArtifacts().stream()
              .filter(a -> a.getFile() != null && a.getFile().getName().endsWith(".jar"))
              .filter(a -> !reactorGAs.contains(ga(a)))
              .filter(a -> isCompileClasspathScope(a.getScope()))
              .map(a -> a.getFile().toPath())
              .toList();
      for (final var a : project.getArtifacts()) {
        result.putIfAbsent(artifactKey(a), projectClasspath);
      }
    }
    return result;
  }

  public static Map<String, Artifact> externalArtifacts(final List<MavenProject> projects) {
    final Set<String> reactorProjects = reactorProjects(projects);
    return projects.stream()
        .flatMap(project -> project.getArtifacts().stream())
        .filter(artifact -> artifact.getFile() != null)
        .filter(artifact -> artifact.getFile().getName().endsWith(".jar"))
        .filter(artifact -> !reactorProjects.contains(ga(artifact)))
        .collect(
            Collectors.toMap(
                ReactorProjects::artifactKey,
                artifact -> artifact,
                (first, ignored) -> first,
                TreeMap::new));
  }

  public static List<RemoteRepository> remoteRepositories(final List<MavenProject> projects) {
    final Map<String, RemoteRepository> repositories =
        projects.stream()
            .flatMap(project -> project.getRemoteProjectRepositories().stream())
            .collect(
                Collectors.toMap(
                    ReactorProjects::repositoryKey,
                    repository -> repository,
                    (first, ignored) -> first,
                    TreeMap::new));
    return List.copyOf(repositories.values());
  }

  public static String moduleRel(final Path workspaceRoot, final MavenProject project) {
    final String rel = workspaceRoot.relativize(project.getBasedir().toPath()).toString();
    return rel.isEmpty() ? "." : rel;
  }

  // A module whose directory lies outside the reactor root (e.g. <module>../shared</module>): its
  // workspace-relative path starts with ".." (or is absolute), so .lathe/<rel> would collapse out
  // of
  // .lathe/ and scatter files. Such a module cannot be mirrored and must be skipped by the sync.
  public static boolean escapesReactorRoot(final Path workspaceRoot, final MavenProject project) {
    final Path rel = workspaceRoot.relativize(project.getBasedir().toPath());
    return rel.isAbsolute() || rel.startsWith("..");
  }

  public static String gav(final MavenProject project) {
    return gav(project.getGroupId(), project.getArtifactId(), project.getVersion());
  }

  public static String gav(final Artifact artifact) {
    return gav(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
  }

  public static String gav(final org.eclipse.aether.artifact.Artifact artifact) {
    return gav(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
  }

  private static String gav(final String g, final String a, final String v) {
    return "%s:%s:%s".formatted(g, a, v);
  }

  private static Set<String> reactorProjects(final List<MavenProject> projects) {
    return projects.stream()
        .map(project -> "%s:%s".formatted(project.getGroupId(), project.getArtifactId()))
        .collect(Collectors.toSet());
  }

  private static String ga(final Artifact artifact) {
    return "%s:%s".formatted(artifact.getGroupId(), artifact.getArtifactId());
  }

  public static String artifactKey(final Artifact artifact) {
    return "%s:%s:%s:%s:%s"
        .formatted(
            artifact.getGroupId(),
            artifact.getArtifactId(),
            artifact.getType(),
            artifact.getClassifier(),
            artifact.getVersion());
  }

  private static String repositoryKey(final RemoteRepository repository) {
    return "%s %s".formatted(repository.getId(), repository.getUrl());
  }

  private static boolean isCompileClasspathScope(final String scope) {
    return Artifact.SCOPE_COMPILE.equals(scope) || Artifact.SCOPE_PROVIDED.equals(scope);
  }
}
