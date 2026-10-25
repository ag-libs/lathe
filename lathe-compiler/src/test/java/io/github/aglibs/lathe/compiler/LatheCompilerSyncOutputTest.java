package io.github.aglibs.lathe.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.CompiledStamps;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.ContentFingerprint;
import io.github.aglibs.lathe.core.schema.SourceStamp;
import io.github.aglibs.validcheck.ValidCheck;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.codehaus.plexus.compiler.CompilerConfiguration;
import org.codehaus.plexus.compiler.CompilerMessage;
import org.codehaus.plexus.compiler.CompilerResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LatheCompilerSyncOutputTest {

  private static final String TREE = LatheLayout.CLASSES_DIR;
  private static final Path FOO_SOURCE = Path.of("com/example/Foo.java");
  private static final SourceStamp OLD_STAMP = new SourceStamp(1L, null);

  @TempDir Path tmp;

  // Maven clears its output before a full recompile, so a failed compile leaves it empty: the
  // mirror must keep the last good bytecode, and the stamps must not claim the sources compiled.
  @Test
  void syncOutput_failedCompile_writesParamsKeepsMirrorsAndStamps() throws Exception {
    final var module = module();
    final var failed =
        new CompilerResult(
            false, List.of(new CompilerMessage("broken", CompilerMessage.Kind.ERROR)));

    LatheCompiler.syncOutput(module.config(), module.latheDir(), Path.of("module-a"), failed);

    assertThat(module.latheDir().resolve(LatheLayout.paramsFileName(TREE))).exists();
    assertThat(module.latheDir().resolve(TREE).resolve("com/example/Old.class")).exists();
    assertThat(generatedMirror(module).resolve("com/example/OldGen.java")).exists();
    assertThat(CompiledStamps.load(module.latheDir(), TREE))
        .containsOnly(Map.entry(FOO_SOURCE, OLD_STAMP));
  }

  @Test
  void syncOutput_successfulCompile_replacesMirrorsAndStamps() throws Exception {
    final var module = module();
    write(module.output().resolve("com/example/Foo.class"), 0L);
    write(module.generated().resolve("com/example/FooGen.java"), 0L);

    LatheCompiler.syncOutput(
        module.config(),
        module.latheDir(),
        Path.of("module-a"),
        new CompilerResult(true, List.of()));

    assertThat(module.latheDir().resolve(TREE).resolve("com/example/Foo.class")).exists();
    assertThat(module.latheDir().resolve(TREE).resolve("com/example/Old.class")).doesNotExist();
    assertThat(generatedMirror(module).resolve("com/example/FooGen.java")).exists();
    assertThat(generatedMirror(module).resolve("com/example/OldGen.java")).doesNotExist();
    assertThat(CompiledStamps.load(module.latheDir(), TREE))
        .containsOnly(
            Map.entry(
                FOO_SOURCE,
                new SourceStamp(
                    100L, ContentFingerprint.of("x".getBytes(StandardCharsets.UTF_8)))));
  }

  // A module whose last good compile is mirrored (Old.class, OldGen.java, a stamp for Foo.java at
  // mtime 1), and whose source Foo.java has since changed (mtime 100). Maven's output starts empty.
  private Module module() throws Exception {
    final var root = tmp.resolve("module-a");
    final var source = root.resolve("src/main/java");
    final var output = Files.createDirectories(root.resolve("target/classes"));
    final var generated =
        Files.createDirectories(root.resolve("target/generated-sources/annotations"));
    final var latheDir = tmp.resolve(".lathe/module-a");
    write(source.resolve(FOO_SOURCE), 100L);
    write(latheDir.resolve(TREE).resolve("com/example/Old.class"), 0L);
    write(
        latheDir.resolve(LatheLayout.generatedSourcesDir(TREE)).resolve("com/example/OldGen.java"),
        0L);
    CompiledStamps.writeAll(latheDir, TREE, Map.of(FOO_SOURCE, OLD_STAMP), Map.of());

    final var config = new CompilerConfiguration();
    config.setOutputLocation(output.toString());
    config.setSourceLocations(List.of(source.toString()));
    config.setGeneratedSourcesDirectory(generated.toFile());
    return new Module(config, output, generated, latheDir);
  }

  private static Path generatedMirror(final Module module) {
    return module.latheDir().resolve(LatheLayout.generatedSourcesDir(TREE));
  }

  private static void write(final Path path, final long mtime) throws Exception {
    Files.createDirectories(path.getParent());
    Files.writeString(path, "x");
    Files.setLastModifiedTime(path, FileTime.fromMillis(mtime));
  }

  private record Module(CompilerConfiguration config, Path output, Path generated, Path latheDir) {

    private Module {
      ValidCheck.check()
          .notNull(config, "config")
          .notNull(output, "output")
          .notNull(generated, "generated")
          .notNull(latheDir, "latheDir")
          .validate();
    }
  }
}
