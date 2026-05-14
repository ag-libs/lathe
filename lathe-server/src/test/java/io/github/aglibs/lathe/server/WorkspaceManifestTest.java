package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceManifestTest extends SampleFixture {

  @Test
  void load_missingFile_returnsEmpty(@TempDir final Path dir) {
    assertThat(WorkspaceManifest.load(dir).originLabel(null, null)).isEmpty();
  }

  @Test
  void originLabel_jdkType_returnsModuleName() {
    final var listType = task.getElements().getTypeElement("java.util.List");
    assertThat(WorkspaceManifest.empty().originLabel(listType, fm)).hasValue("java.base");
  }

  @Test
  void originLabel_jdkType_withVersionInManifest_includesVersion() throws Exception {
    final Path latheDir = tmp.resolve(LatheLayout.LATHE_DIR);
    Files.createDirectories(latheDir);
    Files.writeString(
        latheDir.resolve(LatheLayout.WORKSPACE_PROPERTIES),
        "schemaVersion=1\njdk.version=21.0.1\n");

    final var listType = task.getElements().getTypeElement("java.util.List");
    assertThat(WorkspaceManifest.load(tmp).originLabel(listType, fm))
        .hasValue("java.base (JDK 21.0.1)");
  }

  @Test
  void originLabel_reactorClass_returnsModuleName() throws Exception {
    final Path classDir = tmp.resolve(LatheLayout.LATHE_DIR).resolve("mymodule");
    Files.createDirectories(classDir);
    final Path sampleSrc = tmp.resolve("Sample.java");
    TestCompiler.compileToDir(classDir, sampleSrc);

    final var compiled = TestCompiler.parseWithClasspath(sampleSrc, classDir);
    final var sampleType = compiled.task().getElements().getTypeElement("Sample");

    assertThat(WorkspaceManifest.empty().originLabel(sampleType, compiled.fm()))
        .hasValue("mymodule");
  }

  @Test
  void load_dependencySourceClasspath_returnsSelfJarAndTransitiveJars() throws Exception {
    final Path selfJar = tmp.resolve("repo/self.jar");
    final Path transitiveJar = tmp.resolve("repo/transitive.jar");
    final Path sourceDir = tmp.resolve("sources/self");
    final Path sourceFile = sourceDir.resolve("com/example/Self.java");
    writeWorkspaceManifest(
        """
        dependencySource.0.gav=com.example:self:1
        dependencySource.0.jar=%s
        dependencySource.0.status=present
        dependencySource.0.dir=%s
        dependencySource.0.classpath.0=%s
        """
            .formatted(selfJar, sourceDir, transitiveJar));
    Files.createDirectories(sourceFile.getParent());
    Files.writeString(sourceFile, "package com.example; public class Self {}");

    final WorkspaceManifest manifest = WorkspaceManifest.load(tmp);

    assertThat(manifest.externalSourceRootForFile(sourceFile)).hasValue(sourceDir);
    assertThat(manifest.isExternalSourceFile(sourceFile)).isTrue();
    assertThat(manifest.allSourceDirs()).containsExactly(sourceDir);
    assertThat(manifest.depClasspathForFile(sourceFile)).containsExactly(selfJar, transitiveJar);
  }

  @Test
  void externalSourceRoot_classpathJar_returnsMappedSourceDir() throws Exception {
    final Path sourceDir = tmp.resolve("sources/greeter");
    final Path greeterSrc = sourceDir.resolve("com/example/Greeter.java");
    Files.createDirectories(greeterSrc.getParent());
    Files.writeString(greeterSrc, "package com.example; public class Greeter {}");

    final Path jar = tmp.resolve("repo/greeter.jar");
    Files.createDirectories(jar.getParent());
    TestCompiler.compileToJar(jar, tmp.resolve("classes/greeter"), List.of(), greeterSrc);
    writeWorkspaceManifest(
        """
        dependencySource.0.gav=com.example:greeter:1
        dependencySource.0.jar=%s
        dependencySource.0.status=present
        dependencySource.0.dir=%s
        """
            .formatted(jar, sourceDir));

    final Path userSrc = tmp.resolve("User.java");
    Files.writeString(userSrc, "import com.example.Greeter; public class User { Greeter g; }");
    final var compiled = TestCompiler.parseWithClasspath(userSrc, jar);
    final var greeterElement = compiled.task().getElements().getTypeElement("com.example.Greeter");

    assertThat(WorkspaceManifest.load(tmp).externalSourceRoot(greeterElement, compiled.fm()))
        .hasValue(sourceDir);
  }

  private void writeWorkspaceManifest(final String entries) throws Exception {
    final Path latheDir = tmp.resolve(LatheLayout.LATHE_DIR);
    Files.createDirectories(latheDir);
    Files.writeString(
        latheDir.resolve(LatheLayout.WORKSPACE_PROPERTIES),
        """
        schemaVersion=1
        workspaceRoot=%s
        %s
        """
            .formatted(tmp, entries));
  }
}
