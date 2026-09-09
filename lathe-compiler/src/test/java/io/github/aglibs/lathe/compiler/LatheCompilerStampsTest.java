package io.github.aglibs.lathe.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.CompiledStamps;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.codehaus.plexus.compiler.CompilerConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LatheCompilerStampsTest {

  @TempDir Path tmp;

  @Test
  void writeCompiledStamps_stampsSourcesExcludingGeneratedRoot() throws Exception {
    final var moduleRoot = tmp.resolve("module-a");
    final var srcRoot = moduleRoot.resolve("src/main/java");
    final var genRoot = moduleRoot.resolve("target/generated-sources/annotations");
    final var latheModuleDir = tmp.resolve(".lathe/module-a");
    Files.createDirectories(latheModuleDir);

    write(srcRoot.resolve("com/example/Foo.java"), 100L);
    write(srcRoot.resolve("com/example/Bar.java"), 200L);
    write(genRoot.resolve("com/example/Gen.java"), 300L);

    final var config = new CompilerConfiguration();
    config.setSourceLocations(List.of(srcRoot.toString(), genRoot.toString()));
    config.setGeneratedSourcesDirectory(genRoot.toFile());

    LatheCompiler.writeCompiledStamps(config, latheModuleDir, "classes");

    assertThat(CompiledStamps.load(latheModuleDir, "classes"))
        .containsOnly(
            Map.entry("com/example/Foo.java", 100L), Map.entry("com/example/Bar.java", 200L));
  }

  private static void write(final Path path, final long mtime) throws Exception {
    Files.createDirectories(path.getParent());
    Files.writeString(path, "class X {}");
    Files.setLastModifiedTime(path, FileTime.fromMillis(mtime));
  }
}
