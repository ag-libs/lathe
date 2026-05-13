package io.github.aglibs.lathe.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.apache.maven.model.Dependency;
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
  void externalDependencies_filtersReactorProjectsAndDeduplicates() {
    final MavenProject core = project("com.example", "core", "1", tmp.resolve("core"));
    final MavenProject app = project("com.example", "app", "1", tmp.resolve("app"));
    app.getDependencies().add(dependency("com.example", "core", "1"));
    app.getDependencies().add(dependency("org.example", "external", "1"));
    app.getDependencies().add(dependency("org.example", "external", "1"));

    assertThat(ReactorProjects.externalDependencies(List.of(core, app)).values())
        .extracting(Dependency::getGroupId, Dependency::getArtifactId, Dependency::getVersion)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("org.example", "external", "1"));
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
    final MavenProject project = new TestProject();
    project.setGroupId(groupId);
    project.setArtifactId(artifactId);
    project.setVersion(version);
    project.setFile(basedir.resolve("pom.xml").toFile());
    return project;
  }

  private static Dependency dependency(
      final String groupId, final String artifactId, final String version) {
    final Dependency dependency = new Dependency();
    dependency.setGroupId(groupId);
    dependency.setArtifactId(artifactId);
    dependency.setVersion(version);
    dependency.setType("jar");
    return dependency;
  }

  private static final class TestProject extends MavenProject {

    private List<RemoteRepository> remoteProjectRepositories = List.of();

    @Override
    public List<RemoteRepository> getRemoteProjectRepositories() {
      return remoteProjectRepositories;
    }
  }
}
