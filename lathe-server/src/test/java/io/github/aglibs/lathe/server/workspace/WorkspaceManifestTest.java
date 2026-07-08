package io.github.aglibs.lathe.server.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.DependencyData;
import io.github.aglibs.lathe.core.schema.JdkSourceData;
import io.github.aglibs.lathe.core.schema.SourceStatus;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import io.github.aglibs.lathe.server.TestCompiler;
import io.github.aglibs.lathe.server.analysis.SampleFixture;
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
    final var listType = compiled.task().getElements().getTypeElement("java.util.List");
    assertThat(WorkspaceManifest.empty().originLabel(listType, compiled.fm()))
        .hasValue("java.base");
  }

  @Test
  void originLabel_jdkType_withVersionInManifest_includesVersion() throws Exception {
    final Path latheDir = tmp.resolve(LatheLayout.LATHE_DIR);
    Files.createDirectories(latheDir);
    Json.write(
        new WorkspaceManifestData(
            LatheLayout.SCHEMA_VERSION,
            tmp.toString(),
            null,
            null,
            new JdkSourceData("OpenJDK", "21.0.1", SourceStatus.MISSING, null, null, null, null),
            List.of(),
            List.of()),
        latheDir.resolve(LatheLayout.WORKSPACE_JSON));

    final var listType = compiled.task().getElements().getTypeElement("java.util.List");
    assertThat(WorkspaceManifest.load(tmp).originLabel(listType, compiled.fm()))
        .hasValue("java.base (JDK 21.0.1)");
  }

  @Test
  void originLabel_reactorClass_returnsModuleName() throws Exception {
    final Path classDir = tmp.resolve(LatheLayout.LATHE_DIR).resolve("mymodule");
    Files.createDirectories(classDir);
    final Path sampleSrc = tmp.resolve("Sample.java");
    TestCompiler.compileToDir(classDir, sampleSrc);

    try (final var parsed = TestCompiler.parseWithClasspath(sampleSrc, classDir)) {
      final var sampleType = parsed.task().getElements().getTypeElement("Sample");

      assertThat(WorkspaceManifest.empty().originLabel(sampleType, parsed.fm()))
          .hasValue("mymodule");
    }
  }

  @Test
  void load_dependencySourceClasspath_returnsSelfJarAndTransitiveJars() throws Exception {
    final Path selfJar = tmp.resolve("repo/self.jar");
    final Path transitiveJar = tmp.resolve("repo/transitive.jar");
    final Path sourceDir = tmp.resolve("sources/self");
    final Path sourceFile = sourceDir.resolve("com/example/Self.java");
    writeWorkspaceManifest(
        List.of(
            new DependencyData(
                "com.example:self:1",
                selfJar.toString(),
                SourceStatus.PRESENT,
                sourceDir.toString(),
                List.of(transitiveJar.toString()),
                null)));
    Files.createDirectories(sourceFile.getParent());
    Files.writeString(sourceFile, "package com.example; public class Self {}");

    final WorkspaceManifest manifest = WorkspaceManifest.load(tmp);

    assertThat(manifest.externalSourceRootForFile(sourceFile)).hasValue(sourceDir);
    assertThat(manifest.containsFile(sourceFile)).isTrue();
    assertThat(manifest.externalSourceDirs()).containsExactly(sourceDir);
    assertThat(manifest.depClasspathForFile(sourceFile)).containsExactly(selfJar, transitiveJar);
  }

  @Test
  void load_jdkTypeIndex_includesShardPath() throws Exception {
    final Path typeIndex = tmp.resolve("cache/type-index/jdks/vendor/21/index.json");
    final Path latheDir = tmp.resolve(LatheLayout.LATHE_DIR);
    Files.createDirectories(latheDir);
    Json.write(
        new WorkspaceManifestData(
            LatheLayout.SCHEMA_VERSION,
            tmp.toString(),
            null,
            null,
            new JdkSourceData(
                "OpenJDK", "21.0.1", SourceStatus.MISSING, Path.of("/jdk"), null, null, typeIndex),
            List.of(),
            List.of()),
        latheDir.resolve(LatheLayout.WORKSPACE_JSON));

    assertThat(WorkspaceManifest.load(tmp).typeIndexShardPaths()).containsExactly(typeIndex);
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
        List.of(
            new DependencyData(
                "com.example:greeter:1",
                jar.toString(),
                SourceStatus.PRESENT,
                sourceDir.toString(),
                List.of(),
                null)));

    final Path userSrc = tmp.resolve("User.java");
    Files.writeString(userSrc, "import com.example.Greeter; public class User { Greeter g; }");
    try (final var parsed = TestCompiler.parseWithClasspath(userSrc, jar)) {
      final var greeterElement = parsed.task().getElements().getTypeElement("com.example.Greeter");

      assertThat(WorkspaceManifest.load(tmp).externalSourceRoot(greeterElement, parsed.fm()))
          .hasValue(sourceDir);
    }
  }

  private void writeWorkspaceManifest(final List<DependencyData> deps) throws Exception {
    final Path latheDir = tmp.resolve(LatheLayout.LATHE_DIR);
    Files.createDirectories(latheDir);
    Json.write(
        new WorkspaceManifestData(
            LatheLayout.SCHEMA_VERSION, tmp.toString(), null, null, null, deps, List.of()),
        latheDir.resolve(LatheLayout.WORKSPACE_JSON));
  }
}
