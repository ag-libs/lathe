package io.github.aglibs.lathe.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.lathe.install.SyncException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.maven.model.Build;
import org.apache.maven.model.Extension;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LatheRegistrationTest {

  @TempDir Path workspaceRoot;

  @Test
  void registersLathe_coreExtensionOrRootBuildExtension_isTrue() throws Exception {
    writeExtensionsXml(
        """
        <extensions>
          <extension>
            <groupId>org.example</groupId>
            <artifactId>other-extension</artifactId>
            <version>1</version>
          </extension>
          <extension>
            <groupId> io.github.ag-libs </groupId>
            <artifactId>lathe-maven-extension</artifactId>
            <version>1</version>
          </extension>
        </extensions>
        """);

    assertThat(LatheRegistration.registersLathe(workspaceRoot, project())).isTrue();
    assertThat(
            LatheRegistration.registersLathe(
                workspaceRoot.resolve("elsewhere"),
                project(extension("io.github.ag-libs", "lathe-maven-extension"))))
        .isTrue();
  }

  @Test
  void registersLathe_noMvnDirOrOnlyOtherExtensions_isFalse() throws Exception {
    assertThat(LatheRegistration.registersLathe(workspaceRoot, project())).isFalse();

    writeExtensionsXml(
        """
        <extensions>
          <extension>
            <groupId>org.example</groupId>
            <artifactId>lathe-maven-extension</artifactId>
          </extension>
          <extension>
            <groupId>io.github.ag-libs</groupId>
          </extension>
        </extensions>
        """);

    assertThat(
            LatheRegistration.registersLathe(
                workspaceRoot, project(extension("io.github.ag-libs", "lathe-maven-plugin"))))
        .isFalse();
  }

  // Maven reads a DOCTYPE; nothing it names outside the file is fetched.
  @Test
  void registersLathe_extensionsXmlWithDoctype_isRead() throws Exception {
    writeExtensionsXml(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE extensions SYSTEM "http://example.invalid/extensions.dtd">
        <extensions>
          <extension>
            <groupId>io.github.ag-libs</groupId>
            <artifactId>lathe-maven-extension</artifactId>
            <version>1</version>
          </extension>
        </extensions>
        """);

    assertThat(LatheRegistration.registersLathe(workspaceRoot, project())).isTrue();
  }

  @Test
  void registersLathe_malformedExtensionsXml_throwsNamingTheFile() throws Exception {
    final Path file = writeExtensionsXml("<extensions><extension>");

    assertThatThrownBy(() -> LatheRegistration.registersLathe(workspaceRoot, project()))
        .isInstanceOf(SyncException.class)
        .hasMessageContaining(file.toString());
  }

  private Path writeExtensionsXml(final String content) throws Exception {
    final Path mvnDir = Files.createDirectories(workspaceRoot.resolve(MavenJvmArgs.MVN_DIR));
    return Files.writeString(
        mvnDir.resolve(LatheRegistration.EXTENSIONS_FILE), content, StandardCharsets.UTF_8);
  }

  private static MavenProject project(final Extension... extensions) {
    final var build = new Build();
    for (final Extension extension : extensions) {
      build.addExtension(extension);
    }

    final var project = new MavenProject();
    project.setBuild(build);
    return project;
  }

  private static Extension extension(final String groupId, final String artifactId) {
    final var extension = new Extension();
    extension.setGroupId(groupId);
    extension.setArtifactId(artifactId);
    return extension;
  }
}
