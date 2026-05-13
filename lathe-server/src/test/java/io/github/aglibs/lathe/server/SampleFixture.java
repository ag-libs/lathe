package io.github.aglibs.lathe.server;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.lang.model.element.Element;
import javax.lang.model.element.VariableElement;
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
  Trees trees;

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
    trees = Trees.instance(task);
  }

  Optional<String> hoverAt(final int line, final int character) {
    final var path = pathAt(line, character);
    final var element = SourceLocator.elementAt(trees, path);
    final var type = path != null ? trees.getTypeMirror(path) : null;
    final var javadoc = JavadocLocator.locate(element, trees, List.of()).orElse(null);
    return HoverFormatter.format(element, type, javadoc, null);
  }

  Element elementAt(final int line, final int character) {
    return SourceLocator.elementAt(trees, pathAt(line, character));
  }

  VariableElement parameterAt(final int line, final int character) {
    return SourceLocator.parameterElementAt(trees, pathAt(line, character));
  }

  TreePath pathAt(final int line, final int character) {
    final var offset = SourceLocator.toOffset(cu, line, character);
    return SourceLocator.pathAt(trees, cu, offset);
  }
}
