package io.github.aglibs.lathe.maven.extension;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import java.util.List;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

class LatheRequirementsTest {

  private static MavenProject module(final String artifactId, final String compilerVersion) {
    final var model = new Model();
    model.setArtifactId(artifactId);
    model.setBuild(new Build());
    final var project = new MavenProject(model);
    if (compilerVersion != null) {
      final var compiler = new Plugin();
      compiler.setGroupId(LatheLayout.Maven.MAVEN_COMPILER_PLUGIN_GROUP_ID);
      compiler.setArtifactId(LatheLayout.Maven.MAVEN_COMPILER_PLUGIN_ARTIFACT_ID);
      compiler.setVersion(compilerVersion);
      project.getBuild().addPlugin(compiler);
    }

    return project;
  }

  @Test
  void unmet_supportedMavenAndCompilers_returnsNothing() {
    final List<MavenProject> modules =
        List.of(module("core", "3.13.0"), module("parent", null), module("app", "3.6.2"));

    assertThat(LatheRequirements.unmet("3.6.3", modules)).isEmpty();
    assertThat(LatheRequirements.unmet("3.9.16", modules)).isEmpty();
  }

  @Test
  void unmet_oldMavenAndOldCompilers_onePerProblemWithItsFix() {
    final List<MavenProject> modules =
        List.of(
            module("core", "3.1"),
            module("app", "3.1"),
            module("health", "3.6.0"),
            module("api", "3.13.0"));

    assertThat(LatheRequirements.unmet("3.5.4", modules))
        .containsExactly(
            "Maven 3.5.4 is older than 3.6.3, the oldest Lathe supports: upgrade Maven, or the project's wrapper (mvn wrapper:wrapper -Dmaven=3.9.11)",
            "maven-compiler-plugin 3.1 (core, app) is older than 3.6.2: pin a newer version in <pluginManagement>, for example 3.13.0",
            "maven-compiler-plugin 3.6.0 (health) is older than 3.6.2: pin a newer version in <pluginManagement>, for example 3.13.0");
  }

  @Test
  void unmet_unknownMavenVersion_checksTheCompilersOnly() {
    assertThat(LatheRequirements.unmet(null, List.of(module("core", "3.13.0")))).isEmpty();
    assertThat(LatheRequirements.unmet(null, List.of(module("core", "3.1")))).hasSize(1);
  }
}
