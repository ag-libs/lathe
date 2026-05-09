package io.github.aglibs.lathe.server;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

/**
 * Base class that compiles {@code Sample.java} before each test. Subclasses inherit {@link #cu} and
 * {@link #trees} ready for inspection.
 */
abstract class SampleFixture {

  @TempDir Path tmp;

  CompilationUnitTree cu;
  DocTrees trees;

  @BeforeEach
  void compile() throws IOException {
    final var source =
        new String(
            Objects.requireNonNull(getClass().getResourceAsStream("/Sample.java")).readAllBytes());
    final var sourceFile = tmp.resolve("Sample.java");
    Files.writeString(sourceFile, source);

    final var compiler = ToolProvider.getSystemJavaCompiler();
    final var fm = compiler.getStandardFileManager(null, null, null);
    final var jfo = fm.getJavaFileObjects(sourceFile).iterator().next();
    final var task = (JavacTask) compiler.getTask(null, fm, null, null, null, List.of(jfo));

    cu = task.parse().iterator().next();
    task.analyze();
    trees = DocTrees.instance(task);
  }
}
