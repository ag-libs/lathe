package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
