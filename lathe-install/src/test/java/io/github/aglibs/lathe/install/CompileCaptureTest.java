package io.github.aglibs.lathe.install;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.CompiledStamps;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.ModuleConfigData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompileCaptureTest {

  @TempDir Path tmp;

  // The main and the test compile each mirror their classes and annotation-processor output; the
  // test compile, which runs second, must not overwrite the main compile's generated sources.
  @Test
  void mirror_mainThenTestCompile_keepsEachScopeSeparate() throws Exception {
    final Path moduleDir = latheModuleDir();
    final Path mainGen = tmp.resolve("target/generated-sources/annotations");
    final Path testGen = tmp.resolve("target/generated-test-sources/test-annotations");
    write("target/generated-sources/annotations/com/example/Main.java", 1L);
    write("target/generated-test-sources/test-annotations/com/example/TestGen.java", 1L);
    write("target/classes/com/example/Main.class", 1L);
    write("target/test-classes/com/example/MainTest.class", 1L);

    CompileCapture.mirror(moduleDir, config("classes", mainGen, List.of()));
    CompileCapture.mirror(moduleDir, config("test-classes", testGen, List.of()));

    assertThat(moduleDir.resolve("classes/com/example/Main.class")).exists();
    assertThat(moduleDir.resolve("test-classes/com/example/MainTest.class")).exists();
    assertThat(moduleDir.resolve(LatheLayout.GENERATED_SOURCES).resolve("com/example/Main.java"))
        .exists();
    assertThat(
            moduleDir
                .resolve(LatheLayout.GENERATED_TEST_SOURCES)
                .resolve("com/example/TestGen.java"))
        .exists();
    assertThat(moduleDir.resolve(LatheLayout.GENERATED_SOURCES).resolve("com/example/TestGen.java"))
        .doesNotExist();
  }

  // A stamp per source, keyed root-relative; the annotation-processor output is a source root too
  // but is left out.
  @Test
  void mirror_sourcesAndGeneratedRoot_stampsOnlyTheRealSources() throws Exception {
    final Path moduleDir = latheModuleDir();
    final Path src = tmp.resolve("src/main/java");
    write("src/main/java/com/example/Foo.java", 100L);
    write("src/main/java/com/example/Bar.java", 200L);
    final Path gen = tmp.resolve("target/generated-sources/annotations");
    write("target/generated-sources/annotations/com/example/Gen.java", 300L);
    Files.createDirectories(tmp.resolve("target/classes"));

    CompileCapture.mirror(
        moduleDir, config("classes", gen, List.of(src.toString(), gen.toString())));

    assertThat(CompiledStamps.load(moduleDir, "classes"))
        .containsOnly(
            Map.entry(Path.of("com/example/Foo.java"), 100L),
            Map.entry(Path.of("com/example/Bar.java"), 200L));
  }

  @Test
  void writeParams_writesTheParamsFileNamedByItsSourceTree() throws Exception {
    final Path moduleDir = latheModuleDir();
    final ModuleConfigData config = config("test-classes", null, List.of("src/test/java"));

    CompileCapture.writeParams(moduleDir, config);

    assertThat(
            Json.read(
                moduleDir.resolve(LatheLayout.paramsFileName("test-classes")),
                ModuleConfigData.class))
        .isEqualTo(config);
  }

  // A processor removed from the build leaves no output: its generated types leave the editor too.
  @Test
  void mirror_compileWithoutProcessorOutput_removesTheEarlierGeneratedMirror() throws Exception {
    final Path moduleDir = latheModuleDir();
    final Path gen = tmp.resolve("target/generated-sources/annotations");
    write("target/generated-sources/annotations/com/example/Gen.java", 1L);
    Files.createDirectories(tmp.resolve("target/classes"));
    CompileCapture.mirror(moduleDir, config("classes", gen, List.of()));
    final Path mirrored =
        moduleDir.resolve(LatheLayout.GENERATED_SOURCES).resolve("com/example/Gen.java");
    assertThat(mirrored).exists();

    CompileCapture.mirror(moduleDir, config("classes", null, List.of()));

    assertThat(moduleDir.resolve(LatheLayout.GENERATED_SOURCES)).doesNotExist();
  }

  private Path latheModuleDir() throws Exception {
    return Files.createDirectories(tmp.resolve(".lathe/module-a"));
  }

  private ModuleConfigData config(
      final String sourceTree, final Path generatedSourcesDir, final List<String> sourceRoots) {
    return new ModuleConfigData(
        sourceTree,
        tmp.resolve("target").resolve(sourceTree).toString(),
        generatedSourcesDir != null ? generatedSourcesDir.toString() : null,
        sourceRoots,
        List.of(),
        List.of(),
        List.of(),
        null,
        null,
        false,
        false,
        null,
        List.of(),
        null,
        false);
  }

  private Path write(final String relative, final long mtime) throws Exception {
    final Path file = tmp.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, "class X {}");
    Files.setLastModifiedTime(file, FileTime.fromMillis(mtime));
    return file;
  }
}
