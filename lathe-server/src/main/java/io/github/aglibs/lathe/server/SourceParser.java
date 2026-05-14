package io.github.aglibs.lathe.server;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;

public final class SourceParser {

  private static final Logger LOG = Logger.getLogger(SourceParser.class.getName());

  private final JavaCompiler compiler;
  private final StandardJavaFileManager fm;

  public SourceParser(final JavaCompiler compiler, final StandardJavaFileManager fm) {
    this.compiler = compiler;
    this.fm = fm;
  }

  <T> Optional<T> parse(final Path sourceFile, final BiFunction<Trees, CompilationUnitTree, T> fn) {
    try {
      final JavaFileObject jfo = fm.getJavaFileObjects(sourceFile).iterator().next();
      final JavacTask task = (JavacTask) compiler.getTask(null, fm, null, null, null, List.of(jfo));
      final CompilationUnitTree cu = task.parse().iterator().next();
      return Optional.ofNullable(fn.apply(Trees.instance(task), cu));
    } catch (final IOException | UncheckedIOException e) {
      LOG.log(Level.WARNING, e, () -> "[parse] %s".formatted(sourceFile));
      return Optional.empty();
    }
  }
}
