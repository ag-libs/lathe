package io.github.aglibs.lathe.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import org.codehaus.plexus.compiler.CompilerConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GeneratedSourcesMirrorTest {

  @TempDir Path tmp;

  // The main and test compile both mirror their annotation-processor output, so the test compile
  // (which runs second) must not overwrite the main compile's generated sources.
  @Test
  void mirrorGeneratedSources_mainThenTestCompile_keepScopesSeparate() throws Exception {
    final var moduleDir = latheModuleDir();
    final var mainGen = writeGen("target/generated-sources/annotations", "com/example/Main.java");
    final var testGen =
        writeGen("target/generated-test-sources/test-annotations", "com/example/TestGen.java");

    LatheCompiler.mirrorGeneratedSources(config("classes", mainGen), moduleDir);
    LatheCompiler.mirrorGeneratedSources(config("test-classes", testGen), moduleDir);

    assertThat(mirrored(moduleDir, LatheLayout.GENERATED_SOURCES, "com/example/Main.java"))
        .exists();
    assertThat(mirrored(moduleDir, LatheLayout.GENERATED_TEST_SOURCES, "com/example/TestGen.java"))
        .exists();
    assertThat(mirrored(moduleDir, LatheLayout.GENERATED_SOURCES, "com/example/TestGen.java"))
        .doesNotExist();
  }

  private Path latheModuleDir() throws Exception {
    return Files.createDirectories(tmp.resolve(".lathe/module-a"));
  }

  private Path writeGen(final String genRoot, final String relative) throws Exception {
    final var root = tmp.resolve(genRoot);
    final var file = root.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, "class X {}");
    return root;
  }

  private static CompilerConfiguration config(final String outputTree, final Path genDir) {
    final var config = new CompilerConfiguration();
    config.setOutputLocation(genDir.getParent().getParent().resolve(outputTree).toString());
    config.setGeneratedSourcesDirectory(genDir.toFile());
    return config;
  }

  private static Path mirrored(final Path moduleDir, final String scopeDir, final String relative) {
    return moduleDir.resolve(scopeDir).resolve(relative);
  }
}
