package io.github.aglibs.lathe.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.repository.RemoteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReactorProjectsTest {

  @TempDir Path tmp;

  @Test
  void moduleRel_rootProject_returnsDot() {
    final MavenProject root = project("com.example", "root", "1", tmp);

    assertThat(ReactorProjects.moduleRel(tmp, root)).isEqualTo(".");
  }

  @Test
  void isSameDirectory_sameDirAcrossRepresentationsAndSymlink_true() throws Exception {
    // A single-module build must not false-skip: the top-level basedir and the .mvn-anchored root
    // can differ by a trailing "."/relativity or a symlink yet be the same directory.
    final Path dir = Files.createDirectories(tmp.resolve("module"));
    final Path symlink = Files.createSymbolicLink(tmp.resolve("link"), dir);

    assertThat(ReactorProjects.isSameDirectory(dir, tmp.resolve("module/."))).isTrue();
    assertThat(ReactorProjects.isSameDirectory(dir, symlink)).isTrue();
  }

  @Test
  void isSameDirectory_differentDirs_false() throws Exception {
    // A -pl submodule build: top-level basedir (the submodule) != the reactor root.
    final Path root = Files.createDirectories(tmp.resolve("root"));
    final Path submodule = Files.createDirectories(tmp.resolve("root/submodule"));

    assertThat(ReactorProjects.isSameDirectory(submodule, root)).isFalse();
  }

  @Test
  void externalArtifacts_filtersReactorProjectsAndDeduplicates() {
    final MavenProject core = project("com.example", "core", "1", tmp.resolve("core"));
    final MavenProject app = project("com.example", "app", "1", tmp.resolve("app"));
    final MavenProject test = project("com.example", "test", "1", tmp.resolve("test"));
    app.setArtifacts(
        Set.of(artifact("com.example", "core", "1"), artifact("org.example", "external", "1")));
    test.setArtifacts(Set.of(artifact("org.example", "external", "1")));

    assertThat(ReactorProjects.externalArtifacts(List.of(core, app, test)).values())
        .extracting(Artifact::getGroupId, Artifact::getArtifactId, Artifact::getVersion)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("org.example", "external", "1"));
  }

  @Test
  void externalArtifacts_usesResolvedArtifactsIncludingTransitives() {
    final MavenProject app = project("com.example", "app", "1", tmp.resolve("app"));
    app.setArtifacts(
        Set.of(artifact("org.example", "direct", "1"), artifact("org.example", "transitive", "2")));

    assertThat(ReactorProjects.externalArtifacts(List.of(app)).values())
        .extracting(Artifact::getGroupId, Artifact::getArtifactId, Artifact::getVersion)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("org.example", "direct", "1"),
            org.assertj.core.groups.Tuple.tuple("org.example", "transitive", "2"));
  }

  @Test
  void artifactClasspaths_recordsCompileAndProvidedExternalJars() {
    final MavenProject core = project("com.example", "core", "1", tmp.resolve("core"));
    final MavenProject app = project("com.example", "app", "1", tmp.resolve("app"));
    final Artifact reactor = artifact("com.example", "core", "1");
    final Artifact compile = artifact("org.example", "compile", "1", Artifact.SCOPE_COMPILE);
    final Artifact provided = artifact("org.example", "provided", "1", Artifact.SCOPE_PROVIDED);
    final Artifact runtime = artifact("org.example", "runtime", "1", Artifact.SCOPE_RUNTIME);
    app.setArtifacts(Set.of(reactor, compile, provided, runtime));

    final Map<String, List<Path>> classpaths =
        ReactorProjects.artifactClasspaths(List.of(core, app));

    assertThat(classpaths.get(ReactorProjects.artifactKey(compile)))
        .containsExactlyInAnyOrder(compile.getFile().toPath(), provided.getFile().toPath());
    assertThat(classpaths.get(ReactorProjects.artifactKey(runtime)))
        .containsExactlyInAnyOrder(compile.getFile().toPath(), provided.getFile().toPath());
  }

  @Test
  void remoteRepositories_deduplicatesByIdAndUrl() {
    final MavenProject first = project("com.example", "first", "1", tmp.resolve("first"));
    final MavenProject second = project("com.example", "second", "1", tmp.resolve("second"));
    final RemoteRepository central =
        new RemoteRepository.Builder("central", "default", "https://repo.example").build();
    ((TestProject) first).remoteProjectRepositories = List.of(central);
    ((TestProject) second).remoteProjectRepositories = List.of(central);

    assertThat(ReactorProjects.remoteRepositories(List.of(first, second))).containsExactly(central);
  }

  private static MavenProject project(
      final String groupId, final String artifactId, final String version, final Path basedir) {
    final var project = new TestProject();
    project.setGroupId(groupId);
    project.setArtifactId(artifactId);
    project.setVersion(version);
    project.setFile(basedir.resolve("pom.xml").toFile());
    return project;
  }

  private Artifact artifact(final String groupId, final String artifactId, final String version) {
    return artifact(groupId, artifactId, version, Artifact.SCOPE_COMPILE);
  }

  private Artifact artifact(
      final String groupId, final String artifactId, final String version, final String scope) {
    final Artifact artifact =
        new DefaultArtifact(groupId, artifactId, version, scope, "jar", "", null);
    artifact.setFile(tmp.resolve("%s-%s.jar".formatted(artifactId, version)).toFile());
    return artifact;
  }

  private static final class TestProject extends MavenProject {

    private List<RemoteRepository> remoteProjectRepositories = List.of();

    @Override
    public List<RemoteRepository> getRemoteProjectRepositories() {
      return remoteProjectRepositories;
    }
  }
}
